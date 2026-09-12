/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (C) 2015-present, Xmake Open Source Community.
 */
package io.xmake.project.profile

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.Tag
import io.xmake.project.directory.LegacyProjectDirectory
import io.xmake.project.directory.xmakeProjectDirectories
import io.xmake.project.toolkit.ToolkitManager
import io.xmake.utils.Logger
import org.jdom.Element
import java.util.UUID

private const val PROFILE_ELEMENT_TAG = "XMakeBuildProfile"

private fun profileElements(element: Element): List<Element> =
    element.children.flatMap { child ->
        if (child.name == PROFILE_ELEMENT_TAG) {
            listOf(child)
        } else {
            profileElements(child)
        }
    }

private fun rewriteLegacyWorkingDirectory(profile: Element) {
    val legacy = profile.getChildren("option")
        .firstOrNull { option -> option.getAttributeValue("name") == "workingDirectory" }
        ?: return
    val legacyDirectory = legacy.getAttributeValue("value") ?: legacy.text
    val projectDirectory = profile.getChild("projectDirectory")
    if (projectDirectory == null) {
        profile.addContent(Element("projectDirectory").setAttribute("value", legacyDirectory))
    } else if ((projectDirectory.getAttributeValue("value") ?: projectDirectory.text).isBlank()) {
        projectDirectory.setAttribute("value", legacyDirectory)
    }
    legacy.detach()
}

@Service(Service.Level.PROJECT)
@State(name = "XMakeBuildProfiles", storages = [Storage("xmake.xml")])
class XMakeBuildProfileManager(private val project: Project) :
    PersistentStateComponent<Element> {

    /** The persisted XML schema. */
    data class State(
        var profiles: MutableList<PersistedProfile> = mutableListOf(),
    ) {
        fun toProfiles(): List<XMakeBuildProfile> = profiles.mapNotNull(PersistedProfile::toProfile)

        companion object {
            fun fromProfiles(profiles: List<XMakeBuildProfile>): State = State(
                profiles.map(PersistedProfile::from).toMutableList(),
            )

            fun fromElement(element: Element): State =
                State().also { state ->
                    XmlSerializer.deserializeInto(state, element)
                }

            fun rewriteLegacyWorkingDirectories(element: Element) {
                profileElements(element).forEach(::rewriteLegacyWorkingDirectory)
            }
        }
    }

    // Serialized by XMLB; profile editors never change this identity.
    /** The persisted profile record. Kept separate from [XMakeBuildProfile] so legacy fields
     *  (workingDirectory) can leave the schema without touching the runtime model. */
    @Tag("XMakeBuildProfile")
    data class PersistedProfile(
        var id: String = UUID.randomUUID().toString(),
        var name: String = XMakeBuildProfile.DEFAULT_PROFILE_NAME,
        var toolkitId: String? = null,
        var platform: String = XMakeBuildProfile.USE_XMAKE_DEFAULT,
        var architecture: String = XMakeBuildProfile.USE_XMAKE_DEFAULT,
        var toolchain: String = XMakeBuildProfile.USE_XMAKE_DEFAULT,
        var buildMode: String = XMakeBuildProfile.DEFAULT_BUILD_MODE,
        var buildDirectory: String = "",
        var androidNdkDirectory: String = "",
        var verbose: Boolean = false,
        var configureArguments: String = "",
    ) {
        fun toProfile(): XMakeBuildProfile? {
            if (!XMakeBuildProfile.isValidId(id)) return null
            return XMakeBuildProfile(
                id = id,
                name = name,
                toolkitId = toolkitId,
                platform = platform,
                architecture = architecture,
                toolchain = toolchain,
                buildMode = buildMode,
                buildDirectory = buildDirectory,
                androidNdkDirectory = androidNdkDirectory,
                verbose = verbose,
                configureArguments = configureArguments,
            )
        }

        companion object {
            fun from(profile: XMakeBuildProfile): PersistedProfile = PersistedProfile(
                id = profile.id,
                name = profile.name,
                toolkitId = profile.toolkitId,
                platform = profile.platform,
                architecture = profile.architecture,
                toolchain = profile.toolchain,
                buildMode = profile.buildMode,
                buildDirectory = profile.buildDirectory,
                androidNdkDirectory = profile.androidNdkDirectory,
                verbose = profile.verbose,
                configureArguments = profile.configureArguments,
            )
        }
    }

    private val stateLock = Any()
    private var currentProfiles = listOf<XMakeBuildProfile>()

    override fun getState(): Element = synchronized(stateLock) {
        val element = Element("XMakeBuildProfiles")
        XmlSerializer.serializeInto(State.fromProfiles(currentProfiles), element)
        element
    }

    override fun noStateLoaded() {
        val initialState = listOf(XMakeBuildProfile.createDefault(project))
        synchronized(stateLock) {
            currentProfiles = initialState
        }
    }

    override fun loadState(state: Element) {
        // A malformed ID cannot be remapped safely because run configurations may still refer to it.
        // Isolate that record and let the next state write remove it from the persisted snapshot.
        State.rewriteLegacyWorkingDirectories(state)
        val persistedProfiles = State.fromElement(state)
        val rawProfiles = persistedProfiles.toProfiles()
        val loadedProfiles = XMakeBuildProfile.normalize(rawProfiles)
        val discardedIds = rawProfiles.map(XMakeBuildProfile::id).toSet() -
                loadedProfiles.map(XMakeBuildProfile::id).toSet()
        if (discardedIds.isNotEmpty()) {
            Logger.w(TAG, "Discarding ${discardedIds.size} malformed or duplicate build profiles: IDs $discardedIds")
        }
        val loadedState = loadedProfiles.ifEmpty {
            listOf(XMakeBuildProfile.createDefault(project))
        }
        synchronized(stateLock) {
            currentProfiles = loadedState
        }
        migrateLegacyProjectDirectories(state)
    }

    private fun migrateLegacyProjectDirectories(element: Element) {
        val legacyDirectories = profileElements(element).mapNotNull { profile ->
            val directory = profile.getChild("projectDirectory")
                ?.let { child -> child.getAttributeValue("value") ?: child.text }
                ?.trim()
                .orEmpty()
            if (directory.isBlank()) return@mapNotNull null
            val toolkitId = profile.getChildren("option")
                .firstOrNull { option -> option.getAttributeValue("name") == "toolkitId" }
                ?.getAttributeValue("value")
            val toolkit = if (toolkitId == null) {
                null
            } else {
                // Drop the directory rather than passing toolkit = null: a null toolkit reads as
                // LOCAL in importLegacyDirectory, which would misfile a WSL/SSH path into localDirectory.
                ToolkitManager.getInstance().registeredToolkit(toolkitId, project)
                    ?: return@mapNotNull null
            }
            LegacyProjectDirectory(toolkit, directory)
        }
        if (legacyDirectories.isNotEmpty()) {
            project.xmakeProjectDirectories.migrateLegacyProjectDirectories(legacyDirectories)
        }
    }

    val profiles: List<XMakeBuildProfile>
        get() = synchronized(stateLock) { currentProfiles.map(XMakeBuildProfile::copy) }

    fun findProfile(id: String): XMakeBuildProfile? = synchronized(stateLock) {
        currentProfiles.firstOrNull { it.id == id }?.copy()
    }

    fun replaceProfiles(profiles: List<XMakeBuildProfile>) {
        require(profiles.isNotEmpty()) { "At least one XMake build profile is required" }
        require(profiles.all { XMakeBuildProfile.isValidId(it.id) }) { "XMake build profile IDs are invalid" }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "XMake build profile IDs must be unique" }
        require(profiles.all { it.name.isNotBlank() }) { "XMake build profile names must not be blank" }
        require(profiles.map { it.name.trim() }.distinct().size == profiles.size) {
            "XMake build profile names must be unique"
        }

        val replacement = profiles
            .map { it.copy(name = it.name.trim()) }
        val changed = synchronized(stateLock) {
            if (currentProfiles == replacement) {
                false
            } else {
                currentProfiles = replacement
                true
            }
        }
        if (!changed) return

        publishProfilesChanged()
    }

    internal fun importMigratedProfile(profile: XMakeBuildProfile): XMakeBuildProfile {
        require(XMakeBuildProfile.isValidId(profile.id)) { "Invalid XMake build profile ID: ${profile.id}" }
        val (importedProfile, changed) = synchronized(stateLock) {
            currentProfiles.firstOrNull { existing -> existing.id == profile.id }?.let { existing ->
                return@synchronized existing.copy() to false
            }
            val existingNames = currentProfiles.mapTo(mutableSetOf()) { existing -> existing.name.trim() }
            val imported = profile.copy(name = XMakeBuildProfile.uniqueName(profile.name, existingNames))
            currentProfiles = currentProfiles + imported
            imported.copy() to true
        }
        if (changed) publishProfilesChanged()
        return importedProfile
    }

    internal fun handleToolkitChanges() {
        val toolkitManager = ToolkitManager.getInstance()
        val shouldPublish = synchronized(stateLock) {
            val updatedProfiles = currentProfiles.map { profile ->
                val toolkitId = profile.toolkitId
                if (toolkitId != null && !toolkitManager.isRegistered(toolkitId)) {
                    profile.copy(toolkitId = null)
                } else {
                    profile
                }
            }
            if (updatedProfiles == currentProfiles) {
                false
            } else {
                currentProfiles = updatedProfiles
                true
            }
        }
        if (shouldPublish) publishProfilesChanged()
    }

    private fun publishProfilesChanged() {
        val publish = Runnable {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(TOPIC).profilesChanged()
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            publish.run()
        } else {
            application.invokeLater(publish)
        }
    }

    fun interface Listener {
        fun profilesChanged()
    }

    companion object {
        private const val TAG = "XMakeBuildProfileManager"

        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("XMake build profiles changed", Listener::class.java)
    }
}

val Project.xmakeBuildProfiles: XMakeBuildProfileManager
    get() = getService(XMakeBuildProfileManager::class.java)
        ?: error("Failed to get XMakeBuildProfileManager for $this")
