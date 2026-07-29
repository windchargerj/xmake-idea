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
package io.xmake.project.profile.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.IntelliJSpacingConfiguration
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.toJBEmptyBorder
import com.intellij.ui.layout.ComboBoxPredicate
import io.xmake.project.directory.prepareProjectDirectory
import io.xmake.project.directory.ui.DirectoryBrowser
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitManager
import io.xmake.project.toolkit.ui.ToolkitComboBox
import io.xmake.project.toolkit.ui.ToolkitListItem
import io.xmake.utils.execute.SyncDirection
import io.xmake.utils.execute.transferProjectFiles
import io.xmake.utils.info.XMakeInfo
import io.xmake.utils.info.XMakeInfoManager
import io.xmake.utils.path.WorkingDirectoryResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.event.DocumentEvent

internal class XMakeBuildProfileForm(
    private val project: Project,
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var baselineProfile = XMakeBuildProfile()
    private var toolkit: Toolkit? = null
    private var workingDirectoryHostIdentity: String? = null
    private var workingDirectoryInitializationPending = false
    private var workingDirectoryJob: Job? = null
    private var workingDirectoryRequestId = 0L
    private var profileInfo = XMakeInfo()
    private var profileInfoJob: Job? = null
    private var profileInfoRequestId = 0L
    private var resetting = false
    private var updatingOptions = false

    private val toolkitComboBox = ToolkitComboBox(project, ::toolkit)
    private val platformComboBox = XMakeProfileOptionComboBox()
    private val architectureComboBox = XMakeProfileOptionComboBox()
    private val toolchainComboBox = XMakeProfileOptionComboBox()
    private val modeComboBox = XMakeProfileOptionComboBox()
    private val workingDirectory = DirectoryBrowser(
        project,
        browseTitle = "Working Directory",
        browseDescription = "Select the working directory",
    )
    private val buildDirectory = DirectoryBrowser(
        project,
        browseTitle = "Build Directory",
        browseDescription = "Select the build directory",
    )
    private val androidNdkDirectory = DirectoryBrowser(
        project,
        browseTitle = "Android NDK Directory",
        browseDescription = "Select the Android NDK directory",
    )
    private val verbose = JBCheckBox("Enable verbose output")
    private val additionalConfiguration = RawCommandLineEditor()
    private val profileInfoRefreshTimer = Timer(PROFILE_INFO_REFRESH_DELAY_MS) {
        refreshProfileInfo()
    }.apply {
        isRepeats = false
    }

    val component: JComponent = panel {
        row("XMake toolkit:") {
            cell(toolkitComboBox).align(AlignX.FILL)
        }

        row {
            label("Configuration:").align(AlignY.TOP)
            panel {
                row { label("Platform:") }
                row { cell(platformComboBox).align(AlignX.FILL) }
            }.resizableColumn()
            panel {
                row { label("Architecture:") }
                row { cell(architectureComboBox).align(AlignX.FILL) }
            }.resizableColumn()
            panel {
                row { label("Toolchain:") }
                row { cell(toolchainComboBox).align(AlignX.FILL) }
            }.resizableColumn()
        }.layout(RowLayout.PARENT_GRID)

        separator()

        row("Build mode:") {
            cell(modeComboBox).align(AlignX.FILL)
        }

        collapsibleGroup("Additional Configuration") {
            row("Working directory:") {
                cell(workingDirectory).align(AlignX.FILL)
            }
            row("Build directory:") {
                cell(buildDirectory).align(AlignX.FILL)
            }
            row("Android NDK directory:") {
                cell(androidNdkDirectory).align(AlignX.FILL)
            }
            row("Additional options:") {
                cell(additionalConfiguration).align(AlignX.FILL)
            }
            row {
                cell(verbose)
            }
        }

        row("Project files:") {
            button("Upload") {
                synchronize(SyncDirection.LOCAL_TO_UPSTREAM)
            }
            button("Download") {
                synchronize(SyncDirection.UPSTREAM_TO_LOCAL)
            }
        }.visibleIf(ComboBoxPredicate<ToolkitListItem>(toolkitComboBox) { item ->
            (item as? ToolkitListItem.ToolkitItem)?.toolkit?.isOnRemote == true
        })
    }.apply {
        border = IntelliJSpacingConfiguration().dialogUnscaledGaps.toJBEmptyBorder()
    }

    private val profileInfoVisibilityListener = HierarchyListener { event ->
        if ((event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L) {
            return@HierarchyListener
        }
        if (component.isShowing) {
            onFormShown()
        } else {
            cancelProfileInfoRefresh()
        }
    }

    init {
        Disposer.register(this, toolkitComboBox)
        toolkitComboBox.addToolkitChangedListener { selectedToolkit ->
            if (!resetting) onToolkitChanged(selectedToolkit)
        }
        platformComboBox.addItemListener { event ->
            if (
                event.stateChange == ItemEvent.SELECTED &&
                !resetting &&
                !updatingOptions
            ) {
                updateArchitecturesAfterPlatformChange()
            }
        }
        workingDirectory.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (!resetting) profileInfoRefreshTimer.restart()
            }
        })
        component.addHierarchyListener(profileInfoVisibilityListener)
    }

    fun reset(profile: XMakeBuildProfile) {
        cancelWorkingDirectoryPreparation()
        cancelProfileInfoRefresh()
        profileInfo = XMakeInfo()
        baselineProfile = profile.copy()
        resetting = true
        try {
            toolkit = profile.resolveToolkit(project)
                ?: profile.toolkitId?.let(ToolkitManager.getInstance()::findRegisteredToolkitById)
            workingDirectoryHostIdentity = toolkit?.host?.endpointIdentity
            workingDirectory.text = profile.workingDirectory
            buildDirectory.text = profile.buildDirectory
            androidNdkDirectory.text = profile.androidNdkDirectory
            verbose.isSelected = profile.verbose
            additionalConfiguration.text = profile.additionalConfiguration

            platformComboBox.updateOptions(emptyList(), profile.platform)
            architectureComboBox.updateOptions(emptyList(), profile.architecture)
            toolchainComboBox.updateOptions(emptyList(), profile.toolchain)
            modeComboBox.updateOptions(
                listOf(DEFAULT_MODE, "debug"),
                profile.mode,
                fallback = DEFAULT_MODE,
            )
            toolkitComboBox.selectToolkit(toolkit)
            updateDirectoryBrowsers()
            workingDirectoryInitializationPending = toolkit != null && workingDirectory.text.isBlank()
        } finally {
            resetting = false
        }
        if (component.isShowing) onFormShown()
    }

    fun buildProfile(name: String): XMakeBuildProfile = baselineProfile.copy(
        name = name,
        toolkitId = toolkit?.id,
        platform = platformComboBox.selectedItem?.toString() ?: DEFAULT_VALUE,
        architecture = architectureComboBox.selectedItem?.toString() ?: DEFAULT_VALUE,
        toolchain = toolchainComboBox.selectedItem?.toString() ?: DEFAULT_VALUE,
        mode = modeComboBox.selectedItem?.toString() ?: DEFAULT_MODE,
        workingDirectory = workingDirectory.text,
        buildDirectory = buildDirectory.text,
        androidNdkDirectory = androidNdkDirectory.text,
        verbose = verbose.isSelected,
        additionalConfiguration = additionalConfiguration.text,
    )

    override fun dispose() {
        component.removeHierarchyListener(profileInfoVisibilityListener)
        cancelProfileInfoRefresh()
        cancelWorkingDirectoryPreparation()
        scope.cancel()
    }

    private fun onToolkitChanged(selectedToolkit: Toolkit?) {
        updateDirectoryBrowsers()
        cancelProfileInfoRefresh()
        profileInfo = XMakeInfo()

        if (selectedToolkit == null) {
            cancelWorkingDirectoryPreparation()
            workingDirectoryHostIdentity = null
            workingDirectoryInitializationPending = false
            return
        }

        val hostIdentity = selectedToolkit.host.endpointIdentity
        if (workingDirectoryHostIdentity != hostIdentity) {
            cancelWorkingDirectoryPreparation()
            workingDirectoryHostIdentity = hostIdentity
            workingDirectory.text = ""
        }

        if (workingDirectory.text.isBlank() && workingDirectoryJob?.isActive != true) {
            if (component.isShowing) {
                prepareWorkingDirectory(selectedToolkit)
            } else {
                workingDirectoryInitializationPending = true
            }
        } else {
            refreshProfileInfo()
        }
    }

    private fun updateDirectoryBrowsers() {
        listOf(workingDirectory, buildDirectory, androidNdkDirectory).forEach { browser ->
            browser.setToolkit(toolkit)
        }
    }

    private fun onFormShown() {
        val selectedToolkit = toolkit
        if (workingDirectoryInitializationPending && selectedToolkit != null) {
            workingDirectoryInitializationPending = false
            workingDirectoryHostIdentity = selectedToolkit.host.endpointIdentity
            prepareWorkingDirectory(selectedToolkit)
        }
        refreshProfileInfo()
    }

    private fun refreshProfileInfo() {
        if (!component.isShowing) return

        profileInfoRefreshTimer.stop()
        profileInfoJob?.cancel()
        profileInfoJob = null
        val requestId = ++profileInfoRequestId
        val selectedToolkit = toolkit ?: return
        val requestedProfile = buildProfileForInfo(selectedToolkit) ?: return

        profileInfoJob = scope.launch {
            try {
                val info = XMakeInfoManager.getInstance(project).loadBuildProfileInfo(requestedProfile)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (
                        requestId != profileInfoRequestId ||
                        project.isDisposed ||
                        buildProfileForInfo(toolkit ?: return@withContext) != requestedProfile
                    ) {
                        return@withContext
                    }
                    profileInfoJob = null
                    profileInfo = info
                    updateProfileOptions()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.debug("Unable to load XMake profile options", error)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (requestId == profileInfoRequestId) profileInfoJob = null
                }
            }
        }
    }

    private fun buildProfileForInfo(selectedToolkit: Toolkit): XMakeBuildProfile? {
        if (
            !selectedToolkit.isValid ||
            selectedToolkit.path.isBlank() ||
            selectedToolkit.isOnRemote && selectedToolkit.host.target == null ||
            workingDirectory.text.isBlank()
        ) {
            return null
        }
        return buildProfile(baselineProfile.name).copy(toolkitId = selectedToolkit.id)
    }

    private fun updateProfileOptions() {
        updatingOptions = true
        try {
            platformComboBox.updateOptions(
                profileInfo.platforms + profileInfo.architectures.keys,
                platformComboBox.selectedItem,
            )
            updateArchitectures()
            toolchainComboBox.updateOptions(profileInfo.toolchains.keys, toolchainComboBox.selectedItem)
            modeComboBox.updateOptions(
                profileInfo.buildModes
                    .map { mode -> mode.removePrefix("mode.") }
                    .ifEmpty { listOf(DEFAULT_MODE, "debug") },
                modeComboBox.selectedItem,
                fallback = DEFAULT_MODE,
            )
        } finally {
            updatingOptions = false
        }
    }

    private fun updateArchitectures(selected: Any? = architectureComboBox.selectedItem) {
        val platform = platformComboBox.selectedItem?.toString() ?: DEFAULT_VALUE
        architectureComboBox.updateOptions(profileInfo.architectures[platform].orEmpty(), selected)
    }

    private fun updateArchitecturesAfterPlatformChange() {
        val platform = platformComboBox.selectedItem?.toString() ?: DEFAULT_VALUE
        val architectures = profileInfo.architectures[platform].orEmpty()
        val currentArchitecture = architectureComboBox.selectedItem?.toString()
        val selectedArchitecture = when {
            currentArchitecture in architectures -> currentArchitecture
            baselineProfile.architecture in architectures -> baselineProfile.architecture
            architectures.isNotEmpty() -> architectures.first()
            else -> DEFAULT_VALUE
        }
        updateArchitectures(selectedArchitecture)
    }

    private fun prepareWorkingDirectory(selectedToolkit: Toolkit) {
        cancelWorkingDirectoryPreparation()
        val requestId = ++workingDirectoryRequestId
        val hostIdentity = selectedToolkit.host.endpointIdentity
        workingDirectoryJob = scope.launch {
            try {
                val defaultDirectory = selectedToolkit.prepareProjectDirectory(project)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (requestId != workingDirectoryRequestId || project.isDisposed) {
                        return@withContext
                    }
                    workingDirectoryJob = null
                    if (
                        toolkit?.host?.endpointIdentity == hostIdentity &&
                        workingDirectory.text.isBlank()
                    ) {
                        if (defaultDirectory.isNullOrBlank()) {
                            workingDirectoryInitializationPending = true
                        } else {
                            workingDirectoryInitializationPending = false
                            workingDirectory.text = defaultDirectory
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.debug("Unable to prepare the XMake project directory", error)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (requestId == workingDirectoryRequestId) {
                        workingDirectoryJob = null
                        workingDirectoryInitializationPending =
                            toolkit?.host?.endpointIdentity == hostIdentity && workingDirectory.text.isBlank()
                    }
                }
            }
        }
    }

    private fun cancelWorkingDirectoryPreparation() {
        workingDirectoryJob?.cancel()
        workingDirectoryJob = null
        workingDirectoryRequestId++
    }

    private fun cancelProfileInfoRefresh() {
        profileInfoRefreshTimer.stop()
        profileInfoJob?.cancel()
        profileInfoJob = null
        profileInfoRequestId++
    }

    private fun synchronize(direction: SyncDirection) {
        val selectedToolkit = toolkit ?: return
        val directory = workingDirectory.text
        scope.launch {
            try {
                withBackgroundProgress(project, "Sync XMake project", cancellable = true) {
                    val resolvedDirectory = WorkingDirectoryResolver.resolve(project, directory, selectedToolkit)
                    transferProjectFiles(project, selectedToolkit, direction, resolvedDirectory)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.warn("Unable to synchronize the XMake project", error)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (!project.isDisposed) {
                        Messages.showErrorDialog(
                            project,
                            error.message ?: "Unable to synchronize the XMake project",
                            "XMake Project Sync",
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_VALUE = "default"
        const val DEFAULT_MODE = "release"
        const val PROFILE_INFO_REFRESH_DELAY_MS = 300
        val Log = logger<XMakeBuildProfileForm>()
    }
}
