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
 *
 * @author      ruki
 * @file        ToolkitManager.kt
 *
 */
package io.xmake.project.toolkit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.utils.Logger
import io.xmake.utils.extension.ToolkitHostExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.WeakHashMap

@Service
@State(name = "toolkits", storages = [Storage("xmakeToolkits.xml")])
class ToolkitManager(private val scope: CoroutineScope) : PersistentStateComponent<ToolkitManager.State> {

    class State {
        @XCollection(propertyElementName = "registeredToolKits")
        val registeredToolkits = mutableSetOf<Toolkit>()
        var lastSelectedToolkitId: String? = null
    }

    private val hostExtensions: ExtensionPointName<ToolkitHostExtension> =
        ExtensionPointName("io.xmake.toolkitHostExtension")
    private val detector = ToolkitDetector(hostExtensions)
    private val stateLock = Any()
    private val detectionLock = Any()
    private val globalDetectedToolkits = linkedMapOf<String, Toolkit>()
    private val detectedToolkitsByProject = WeakHashMap<Project, MutableMap<String, Toolkit>>()
    private val detectionJobs = mutableMapOf<Project?, Job>()
    private var storage = State()

    fun requestDetection(project: Project?) {
        if (project?.isDisposed == true) return

        val job = synchronized(detectionLock) {
            if (detectionJobs[project]?.isActive == true) return@synchronized null
            scope.launch(start = CoroutineStart.LAZY) {
                detectToolkits(project)
            }.also { detectionJobs[project] = it }
        } ?: return

        job.invokeOnCompletion {
            synchronized(detectionLock) {
                if (detectionJobs[project] === job) detectionJobs.remove(project)
            }
        }
        job.start()
    }

    private suspend fun detectToolkits(project: Project?) {
        val detectedIds = mutableSetOf<String>()
        try {
            detector.detect(project).collect { detectedToolkit ->
                val toolkit = reconcileDetectedToolkit(detectedToolkit)
                rememberDetectedToolkit(project, toolkit)
                detectedIds.add(toolkit.id)
                publishToolkitChanged(project, toolkit)
                Logger.i(TAG, "toolkit added: $toolkit")
            }
            retainDetectedToolkits(project, detectedIds)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to detect XMake toolkits", error)
        } finally {
            publishDetectionFinished(project)
        }
    }

    override fun getState(): State = storage

    override fun loadState(state: State) {
        val toolkits = synchronized(stateLock) {
            storage = state
            globalDetectedToolkits.clear()
            detectedToolkitsByProject.clear()
            state.registeredToolkits.onEach { toolkit ->
                toolkit.isRegistered = true
                toolkit.isValid = !toolkit.isOnRemote
            }.toList()
        }
        toolkits.forEach(::loadToolkit)
    }

    private fun loadToolkit(toolkit: Toolkit) {
        scope.launch(Dispatchers.IO) {
            try {
                toolkit.host.loadTarget()
                toolkit.isValid = !toolkit.isOnRemote || toolkit.host.target != null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toolkit.isValid = false
                Logger.w(TAG, "Failed to restore toolkit ${toolkit.id}: ${error.message.orEmpty()}")
            }
            reconcileLoadedToolkit(toolkit)?.let { loadedToolkit ->
                publishToolkitChanged(null, loadedToolkit)
            }
        }
    }

    fun registerToolkit(toolkit: Toolkit): Toolkit {
        val registeredToolkit = synchronized(stateLock) {
            reconcileRegisteredToolkit(toolkit) ?: toolkit.also { newToolkit ->
                newToolkit.isRegistered = true
                storage.registeredToolkits.removeIf { registered -> registered.id == newToolkit.id }
                storage.registeredToolkits.add(newToolkit)
            }
        }

        registeredToolkit.isRegistered = true
        if (registeredToolkit.isOnRemote && registeredToolkit.host.target == null) {
            registeredToolkit.isValid = false
            loadToolkit(registeredToolkit)
        }
        publishToolkitChanged(null, registeredToolkit)
        Logger.i(TAG, "load registered toolkit: ${registeredToolkit.name}, ${registeredToolkit.id}")
        return registeredToolkit
    }

    fun unregisterToolkit(toolkit: Toolkit) {
        val registeredToolkit = synchronized(stateLock) {
            findRegisteredToolkit(toolkit)?.also(storage.registeredToolkits::remove)
        } ?: return

        registeredToolkit.isRegistered = false
        toolkit.isRegistered = false
        synchronized(stateLock) {
            detectedToolkitMaps().forEach { detectedToolkits ->
                detectedToolkits.values
                    .filter { detected -> detected.hasSameInstallationAs(registeredToolkit) }
                    .forEach { detected -> detected.isRegistered = false }
            }
        }
        publishToolkitRemoved(registeredToolkit.id)
    }

    fun findRegisteredToolkitById(id: String): Toolkit? =
        synchronized(stateLock) { findRegisteredToolkitByIdUnlocked(id) }

    internal fun getKnownToolkits(project: Project?): List<Toolkit> = synchronized(stateLock) {
        linkedMapOf<String, Toolkit>().apply {
            storage.registeredToolkits
                .filter(::isAvailableToolkit)
                .forEach { toolkit -> put(toolkit.id, toolkit) }
            globalDetectedToolkits.values.forEach { toolkit -> putIfAbsent(toolkit.id, toolkit) }
            project?.let { currentProject ->
                detectedToolkitsByProject[currentProject]?.values?.forEach { toolkit ->
                    putIfAbsent(toolkit.id, toolkit)
                }
            }
        }.values.toList()
    }

    fun getRegisteredToolkits(): List<Toolkit> = synchronized(stateLock) {
        storage.registeredToolkits.filter(::isAvailableToolkit)
    }

    private fun isAvailableToolkit(toolkit: Toolkit): Boolean =
        toolkit.host.type != SSH || sshHostExtensions().any { extension ->
            extension.filterRegistered()(toolkit)
        }

    private fun reconcileDetectedToolkit(toolkit: Toolkit): Toolkit = synchronized(stateLock) {
        reconcileRegisteredToolkit(toolkit) ?: toolkit
    }

    private fun reconcileLoadedToolkit(toolkit: Toolkit): Toolkit? = synchronized(stateLock) {
        val registeredToolkit = findRegisteredToolkitByIdUnlocked(toolkit.id)
            ?: findRegisteredToolkit(toolkit)
            ?: return@synchronized null
        registeredToolkit.host.target = toolkit.host.target
        registeredToolkit.isValid = toolkit.isValid
        registeredToolkit
    }

    private fun reconcileRegisteredToolkit(toolkit: Toolkit): Toolkit? {
        val registeredToolkit = findRegisteredToolkit(toolkit) ?: return null
        val updatedToolkit = toolkit.copy(id = registeredToolkit.id).apply {
            isRegistered = true
            isValid = toolkit.isValid
        }
        if (registeredToolkit == updatedToolkit) {
            registeredToolkit.host.target = toolkit.host.target
            registeredToolkit.isRegistered = true
            registeredToolkit.isValid = toolkit.isValid
            return registeredToolkit
        }

        storage.registeredToolkits.remove(registeredToolkit)
        storage.registeredToolkits.add(updatedToolkit)
        return updatedToolkit
    }

    private fun findRegisteredToolkit(toolkit: Toolkit): Toolkit? =
        storage.registeredToolkits.firstOrNull { registered -> registered.hasSameInstallationAs(toolkit) }
            ?: findRegisteredToolkitByIdUnlocked(toolkit.id)

    private fun findRegisteredToolkitByIdUnlocked(id: String): Toolkit? =
        storage.registeredToolkits.find { toolkit -> toolkit.id == id }

    private fun rememberDetectedToolkit(project: Project?, toolkit: Toolkit) = synchronized(stateLock) {
        val toolkits = detectedToolkits(project)
        toolkits.entries.removeIf { (id, knownToolkit) ->
            id != toolkit.id && knownToolkit.hasSameInstallationAs(toolkit)
        }
        toolkits[toolkit.id] = toolkit
    }

    private fun retainDetectedToolkits(project: Project?, detectedIds: Set<String>) = synchronized(stateLock) {
        detectedToolkits(project).keys.retainAll(detectedIds)
    }

    private fun detectedToolkits(project: Project?): MutableMap<String, Toolkit> =
        project?.let { currentProject -> detectedToolkitsByProject.getOrPut(currentProject, ::linkedMapOf) }
            ?: globalDetectedToolkits

    private fun detectedToolkitMaps(): Sequence<MutableMap<String, Toolkit>> = sequence {
        yield(globalDetectedToolkits)
        yieldAll(detectedToolkitsByProject.values)
    }

    private fun sshHostExtensions(): List<ToolkitHostExtension> =
        hostExtensions.extensionList.filter { extension -> extension.KEY == "SSH" }

    private fun publishToolkitChanged(project: Project?, toolkit: Toolkit) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        application.messageBus.syncPublisher(ToolkitListener.TOPIC).toolkitChanged(project, toolkit)
    }

    private fun publishToolkitRemoved(toolkitId: String) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        application.messageBus.syncPublisher(ToolkitListener.TOPIC).toolkitRemoved(toolkitId)
    }

    private fun publishDetectionFinished(project: Project?) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        application.messageBus.syncPublisher(ToolkitListener.TOPIC).detectionFinished(project)
    }

    companion object {
        private const val TAG = "ToolkitManager"

        fun getInstance(): ToolkitManager =
            serviceOrNull() ?: error("Failed to get ToolkitManager")
    }
}
