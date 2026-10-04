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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.xmake.project.directory.xmakeProjectDirectories
import io.xmake.project.toolkit.ToolkitManager
import org.jdom.Element

/** Serves the project's XMake build profiles from the configured [XMakeBuildProfileStorage]. */
@Service(Service.Level.PROJECT)
@State(name = "XMakeBuildProfiles", storages = [Storage("xmake.xml")])
class XMakeBuildProfileManager(private val project: Project) :
    PersistentStateComponent<Element>,
    Disposable {

    private val stateLock = Any()
    private var storageScope = XMakeBuildProfileStorage.PROJECT
    private var currentProfiles = listOf<XMakeBuildProfile>()

    /** Legacy working directories whose toolkit is not registered yet; they stay in the
     *  persisted snapshot until the toolkit appears instead of being dropped. */
    private var pendingLegacyDirectories: Map<String, XMakeBuildProfileXml.PendingLegacyDirectory> = emptyMap()

    init {
        // Shared-list edits in one project must refresh every project using the shared storage.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            XMakeSharedBuildProfiles.TOPIC,
            XMakeSharedBuildProfiles.Listener { onSharedProfilesChanged() },
        )
    }

    override fun dispose() {
        // The message bus connection is parented to this service and disposed with it.
    }

    override fun getState(): Element = synchronized(stateLock) {
        Element("XMakeBuildProfiles").also { element ->
            element.setAttribute(STORAGE_ATTRIBUTE, storageScope.name)
            if (storageScope == XMakeBuildProfileStorage.PROJECT) {
                // The legacy directories ride along with the profile elements for older versions.
                XMakeBuildProfileXml.writeProfiles(element, currentProfiles)
                XMakeBuildProfileXml.writeLegacyWorkingDirectories(element, pendingLegacyDirectories)
            } else {
                // No profile elements are written, so the pending legacy directories are
                // persisted standalone instead of being silently dropped on save.
                XMakeBuildProfileXml.writePendingLegacyDirectories(element, pendingLegacyDirectories)
            }
        }
    }

    override fun noStateLoaded() {
        val initialState = listOf(XMakeBuildProfile.createDefault(project))
        synchronized(stateLock) {
            currentProfiles = initialState
        }
    }

    override fun loadState(state: Element) {
        val loadedScope = state.getAttributeValue(STORAGE_ATTRIBUTE)
            ?.let { attribute -> XMakeBuildProfileStorage.entries.firstOrNull { it.name == attribute } }
            ?: XMakeBuildProfileStorage.PROJECT
        // A malformed ID cannot be remapped safely because run configurations may still refer to it.
        // Isolate that record and let the next state write remove it from the persisted snapshot.
        val rawProfiles = XMakeBuildProfileXml.readProfiles(state)
        val loadedProfiles = XMakeBuildProfile.normalize(rawProfiles)
        val discardedIds = rawProfiles.map(XMakeBuildProfile::id).toSet() -
                loadedProfiles.map(XMakeBuildProfile::id).toSet()
        if (discardedIds.isNotEmpty()) {
            Log.warn("Discarding ${discardedIds.size} malformed or duplicate build profiles: IDs $discardedIds")
        }
        val loadedState = loadedProfiles.ifEmpty {
            listOf(XMakeBuildProfile.createDefault(project))
        }
        val standalonePending = XMakeBuildProfileXml.readStandalonePendingLegacyDirectories(state)
        val pendingDirectories = standalonePending
            ?: XMakeBuildProfileXml.readLegacyWorkingDirectories(state)
        // Only per-profile entries are bound to a local profile element; standalone entries
        // (shared storage) reference profiles that may live outside the project file.
        val orphanedIds = if (standalonePending != null) {
            emptySet()
        } else {
            pendingDirectories.keys - loadedState.mapTo(mutableSetOf(), XMakeBuildProfile::id)
        }
        if (orphanedIds.isNotEmpty()) {
            Log.warn("Discarding legacy working directories of malformed profiles: IDs $orphanedIds")
        }
        synchronized(stateLock) {
            storageScope = loadedScope
            currentProfiles = loadedState
            pendingLegacyDirectories = pendingDirectories - orphanedIds
        }
        if (loadedScope == XMakeBuildProfileStorage.SHARED) {
            adoptSharedProfiles()
        }
        migratePendingLegacyDirectories()
    }

    /** Where this project's profile list is persisted; see [switchStorage]. */
    val storage: XMakeBuildProfileStorage
        get() = synchronized(stateLock) { storageScope }

    /**
     * Moves the project's profile list to the given storage.
     *
     * Switching to [XMakeBuildProfileStorage.SHARED] merges the project's profiles into the
     * shared list (profiles with an already present id are skipped) and the project then uses
     * the shared list. Switching back copies the current shared list into the project; the
     * shared list itself is left untouched for the other projects using it.
     */
    fun switchStorage(scope: XMakeBuildProfileStorage) {
        if (scope == synchronized(stateLock) { storageScope }) return

        when (scope) {
            XMakeBuildProfileStorage.SHARED -> {
                // Resolve what is resolvable while the local profile list still owns the record.
                migratePendingLegacyDirectories()
                val localProfiles = synchronized(stateLock) { currentProfiles.map(XMakeBuildProfile::copy) }
                val shared = XMakeSharedBuildProfiles.getInstance()
                val imported = shared.importProfiles(localProfiles)
                synchronized(stateLock) {
                    storageScope = scope
                    currentProfiles = imported
                }
            }

            XMakeBuildProfileStorage.PROJECT -> {
                val sharedProfiles = XMakeSharedBuildProfiles.getInstance().profiles
                synchronized(stateLock) {
                    storageScope = scope
                    currentProfiles = sharedProfiles
                }
            }
        }
        publishProfilesChanged()
    }

    /** Migrates pending legacy directories whose toolkit has appeared; the rest are retained in
     *  the persisted snapshot until their toolkit resolves. */
    private fun migratePendingLegacyDirectories() {
        val (migratable, remaining) = synchronized(stateLock) {
            XMakeBuildProfileXml.resolveLegacyWorkingDirectories(pendingLegacyDirectories) { toolkitId ->
                ToolkitManager.getInstance().registeredToolkit(toolkitId, project)
            }
        }
        if (migratable.isNotEmpty()) {
            project.xmakeProjectDirectories.migrateLegacyProjectDirectories(migratable)
        }
        synchronized(stateLock) {
            pendingLegacyDirectories = remaining
        }
    }

    private fun onSharedProfilesChanged() {
        if (project.isDisposed) return
        val shouldPublish = synchronized(stateLock) {
            if (storageScope != XMakeBuildProfileStorage.SHARED) return
            adoptSharedProfilesLocked()
        }
        if (shouldPublish) publishProfilesChanged()
    }

    /** Adopts the current shared list after a scope switch or a shared-list change;
     *  returns whether the project's list changed. */
    private fun adoptSharedProfiles(): Boolean =
        synchronized(stateLock) { adoptSharedProfilesLocked() }

    private fun adoptSharedProfilesLocked(): Boolean {
        val sharedProfiles = XMakeSharedBuildProfiles.getInstance().profiles
        if (currentProfiles == sharedProfiles) return false
        currentProfiles = sharedProfiles
        return true
    }

    val profiles: List<XMakeBuildProfile>
        get() = synchronized(stateLock) { currentProfiles.map(XMakeBuildProfile::copy) }

    fun findProfile(id: String): XMakeBuildProfile? = synchronized(stateLock) {
        currentProfiles.firstOrNull { it.id == id }?.copy()
    }

    fun replaceProfiles(profiles: List<XMakeBuildProfile>) {
        XMakeBuildProfile.validateProfileList(profiles)

        val replacement = profiles
            .map { it.copy(name = it.name.trim()) }
        if (synchronized(stateLock) { storageScope } == XMakeBuildProfileStorage.SHARED) {
            // The synchronous shared-list notification adopts the new list; cover the unchanged
            // case for this project as well.
            XMakeSharedBuildProfiles.getInstance().replaceProfiles(replacement)
            if (adoptSharedProfiles()) publishProfilesChanged()
            return
        }

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
        if (synchronized(stateLock) { storageScope } == XMakeBuildProfileStorage.SHARED) {
            val imported = XMakeSharedBuildProfiles.getInstance()
                .importProfiles(listOf(profile))
                .firstOrNull { merged -> merged.id == profile.id }
                ?: profile
            if (adoptSharedProfiles()) publishProfilesChanged()
            return imported
        }

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
        val activeProfiles = synchronized(stateLock) { currentProfiles.map(XMakeBuildProfile::copy) }
        val updatedProfiles = activeProfiles.map { profile ->
            val toolkitId = profile.toolkitId
            if (toolkitId != null && !toolkitManager.isRegistered(toolkitId)) {
                profile.copy(toolkitId = null)
            } else {
                profile
            }
        }
        if (updatedProfiles != activeProfiles) {
            if (synchronized(stateLock) { storageScope } == XMakeBuildProfileStorage.SHARED) {
                // Toolkit registrations are application-wide, so cleaning the shared list is
                // correct for every project using it.
                XMakeSharedBuildProfiles.getInstance().replaceProfiles(updatedProfiles)
                if (adoptSharedProfiles()) publishProfilesChanged()
            } else {
                synchronized(stateLock) { currentProfiles = updatedProfiles }
                publishProfilesChanged()
            }
        }
        // A newly registered or scanned toolkit may resolve retained legacy directories.
        migratePendingLegacyDirectories()
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
            application.invokeLater(publish, ModalityState.any())
        }
    }

    fun interface Listener {
        fun profilesChanged()
    }

    companion object {
        private val Log = logger<XMakeBuildProfileManager>()

        private const val STORAGE_ATTRIBUTE = "storage"

        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("XMake build profiles changed", Listener::class.java)
    }
}

val Project.xmakeBuildProfiles: XMakeBuildProfileManager
    get() = getService(XMakeBuildProfileManager::class.java)
        ?: error("Failed to get XMakeBuildProfileManager for $this")
