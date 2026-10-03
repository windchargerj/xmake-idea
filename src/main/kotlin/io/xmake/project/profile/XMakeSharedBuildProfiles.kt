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
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.messages.Topic
import io.xmake.project.toolkit.ToolkitManager
import org.jdom.Element

/** Where a project's XMake build profile list is persisted. */
enum class XMakeBuildProfileStorage {
    /** Profiles live in the project files (`.idea/xmake.xml`). */
    PROJECT,

    /** Profiles come from the IDE-wide shared list in [XMakeSharedBuildProfiles]. */
    SHARED,
}

/** The IDE-wide build profile list shared by projects using [XMakeBuildProfileStorage.SHARED]. */
@Service(Service.Level.APP)
@State(name = "XMakeSharedBuildProfiles", storages = [Storage("xmakeBuildProfiles.xml")])
class XMakeSharedBuildProfiles : PersistentStateComponent<Element> {

    private val stateLock = Any()
    private var currentProfiles = listOf<XMakeBuildProfile>()

    override fun getState(): Element = synchronized(stateLock) {
        Element(ELEMENT_NAME).also { element -> XMakeBuildProfileXml.writeProfiles(element, currentProfiles) }
    }

    override fun noStateLoaded() {
        synchronized(stateLock) {
            currentProfiles = listOf(defaultSharedProfile())
        }
    }

    override fun loadState(state: Element) {
        val rawProfiles = XMakeBuildProfileXml.readProfiles(state)
        val loadedProfiles = XMakeBuildProfile.normalize(rawProfiles)
        val discardedIds = rawProfiles.map(XMakeBuildProfile::id).toSet() -
                loadedProfiles.map(XMakeBuildProfile::id).toSet()
        if (discardedIds.isNotEmpty()) {
            Log.warn("Discarding ${discardedIds.size} malformed or duplicate shared build profiles: IDs $discardedIds")
        }
        synchronized(stateLock) {
            currentProfiles = loadedProfiles.ifEmpty { listOf(defaultSharedProfile()) }
        }
    }

    val profiles: List<XMakeBuildProfile>
        get() = synchronized(stateLock) { currentProfiles.map(XMakeBuildProfile::copy) }

    fun replaceProfiles(profiles: List<XMakeBuildProfile>) {
        XMakeBuildProfile.validateProfileList(profiles)
        val replacement = profiles.map { profile -> profile.copy(name = profile.name.trim()) }
        val changed = synchronized(stateLock) {
            if (currentProfiles == replacement) {
                false
            } else {
                currentProfiles = replacement
                true
            }
        }
        if (changed) publishProfilesChanged()
    }

    /** Merges profiles by id with unique names and returns the resulting list snapshot. */
    fun importProfiles(profiles: List<XMakeBuildProfile>): List<XMakeBuildProfile> {
        var changed = false
        val snapshot = synchronized(stateLock) {
            val existingIds = currentProfiles.mapTo(mutableSetOf(), XMakeBuildProfile::id)
            val existingNames = currentProfiles.mapTo(mutableSetOf()) { profile -> profile.name.trim() }
            val mergedList = currentProfiles.toMutableList()
            profiles.forEach { profile ->
                if (profile.id in existingIds) return@forEach
                val imported = profile.copy(
                    name = XMakeBuildProfile.uniqueName(profile.name, existingNames),
                )
                existingIds += imported.id
                existingNames += imported.name
                mergedList += imported
                changed = true
            }
            if (changed) currentProfiles = mergedList
            currentProfiles.map(XMakeBuildProfile::copy)
        }
        if (changed) publishProfilesChanged()
        return snapshot
    }

    private fun publishProfilesChanged() {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        application.messageBus.syncPublisher(TOPIC).profilesChanged()
    }

    fun interface Listener {
        fun profilesChanged()
    }

    companion object {
        private const val ELEMENT_NAME = "XMakeSharedBuildProfiles"
        private val Log = logger<XMakeSharedBuildProfiles>()

        val TOPIC: Topic<Listener> = Topic.create("XMake shared build profiles changed", Listener::class.java)

        fun getInstance(): XMakeSharedBuildProfiles = ApplicationManager.getApplication()
            .getService(XMakeSharedBuildProfiles::class.java)
            ?: error("Failed to get XMakeSharedBuildProfiles")

        /** The shared list has no project context, so the default toolkit reference is the
         *  application-level preference without per-project resolution. */
        private fun defaultSharedProfile(): XMakeBuildProfile = XMakeBuildProfile(
            name = XMakeBuildProfile.DEFAULT_PROFILE_NAME,
            toolkitId = ToolkitManager.getInstance().defaultToolkitId,
        )
    }
}
