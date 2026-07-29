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

import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitListener

@Service(Service.Level.PROJECT)
@State(name = "XMakeBuildProfiles", storages = [Storage("xmake.xml")])
class XMakeBuildProfileManager(private val project: Project) :
    PersistentStateComponent<XMakeBuildProfileManager.State> {
    data class State(
        var profiles: MutableList<XMakeBuildProfile> = mutableListOf(),
    )

    private var profileState = State()

    init {
        project.messageBus.connect().subscribe(
            ToolkitListener.TOPIC,
            object : ToolkitListener {
                override fun toolkitChanged(sourceProject: Project?, toolkit: Toolkit) {
                    profilesChanged(toolkit.id)
                }

                override fun toolkitRemoved(toolkitId: String) {
                    clearToolkit(toolkitId)
                }
            },
        )
    }

    override fun getState(): State = profileState

    override fun loadState(state: State) {
        profileState = State(
            state.profiles
                .asSequence()
                .filter { XMakeBuildProfile.isValidId(it.id) }
                .distinctBy(XMakeBuildProfile::id)
                .map(XMakeBuildProfile::copy)
                .toMutableList(),
        )
    }

    fun getProfiles(): List<XMakeBuildProfile> = ensureProfiles().map(XMakeBuildProfile::copy)

    fun findProfile(id: String): XMakeBuildProfile? =
        ensureProfiles().firstOrNull { it.id == id }?.copy()

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
            .toMutableList()
        if (profileState.profiles == replacement) return

        profileState = State(replacement)
        profilesChanged()
    }

    fun importProfile(profile: XMakeBuildProfile): XMakeBuildProfile {
        require(XMakeBuildProfile.isValidId(profile.id)) { "Invalid XMake build profile ID: ${profile.id}" }
        profileState.profiles.firstOrNull { it.id == profile.id }?.let { return it.copy() }
        val imported = profile.copy(name = uniqueName(profile.name))
        profileState.profiles.add(imported)
        profilesChanged()
        return imported.copy()
    }

    fun clearToolkit(toolkitId: String) {
        var changed = false
        profileState.profiles.replaceAll { profile ->
            if (profile.toolkitId != toolkitId) return@replaceAll profile
            changed = true
            profile.copy(toolkitId = null)
        }
        if (changed) profilesChanged()
    }

    private fun ensureProfiles(): MutableList<XMakeBuildProfile> {
        if (profileState.profiles.isEmpty()) {
            profileState.profiles.add(XMakeBuildProfile.defaultFor(project))
        }
        return profileState.profiles
    }

    private fun uniqueName(requestedName: String): String {
        val baseName = requestedName.trim().ifBlank { "Profile" }
        val existingNames = profileState.profiles
            .mapTo(mutableSetOf()) { it.name.trim() }
        if (baseName !in existingNames) return baseName

        var suffix = 2
        while ("$baseName $suffix" in existingNames) suffix++
        return "$baseName $suffix"
    }

    private fun profilesChanged(affectedToolkitId: String? = null) {
        val notifyPlatform = Runnable {
            if (project.isDisposed) return@Runnable
            if (
                affectedToolkitId != null &&
                profileState.profiles.none { profile -> profile.toolkitId == affectedToolkitId }
            ) {
                return@Runnable
            }
            ExecutionTargetManager.update(project)
            project.messageBus.syncPublisher(TOPIC).profilesChanged()
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            notifyPlatform.run()
        } else {
            application.invokeLater(notifyPlatform)
        }
    }

    fun interface Listener {
        fun profilesChanged()
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("XMake build profiles changed", Listener::class.java)
    }
}

val Project.xmakeBuildProfiles: XMakeBuildProfileManager
    get() = getService(XMakeBuildProfileManager::class.java)
        ?: error("Failed to get XMakeBuildProfileManager for $this")
