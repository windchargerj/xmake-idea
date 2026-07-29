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
package io.xmake.run

import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.EditorTextField
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.xmake.debug.DapDriverDetector
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.profile.XMakeBuildProfileManager
import io.xmake.project.target.XMakeTargetManager
import io.xmake.run.target.activeXMakeBuildProfile
import io.xmake.run.target.xmakeBuildProfile
import io.xmake.utils.ui.LiveModelComboBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

class XMakeRunConfigurationEditor(
    private val project: Project,
) : SettingsEditor<XMakeRunConfiguration>() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val targetModel = DefaultComboBoxModel<String>()
    private val targetComboBox = LiveModelComboBox(targetModel)
    private val runArguments = RawCommandLineEditor()
    private val environmentVariables = EnvironmentVariablesComponent(project)

    private val dapDriverAutoDetect = JBCheckBox("Auto-detect DAP driver")
    private val dapDriverPath = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.singleFile().apply {
            title = "Select DAP Driver"
            description = "Select the lldb-dap or gdb executable"
        }
        addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
    }
    private val launchConfiguration = EditorTextField().apply {
        setOneLineMode(false)
        preferredSize = Dimension(400, 140)
        addSettingsProvider { editor ->
            editor.settings.apply {
                isFoldingOutlineShown = false
                isLineNumbersShown = false
                isCaretRowShown = true
                isAllowSingleLogicalLineFolding = false
                isDndEnabled = false
                isAnimatedScrolling = true
            }
        }
    }
    private val scrollableLaunchConfiguration: JComponent = JBScrollPane(launchConfiguration).apply {
        preferredSize = Dimension(400, 140)
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    private var targetJob: Job? = null
    private var targetRequestId = 0L
    private var targetChoicesProfile: XMakeBuildProfile? = null
    private var resetting = false
    private val targetConnection = project.messageBus.connect()

    init {
        setTargetChoices(DEFAULT_TARGET, emptyList())
        targetConnection.subscribe(ExecutionTargetManager.TOPIC, ExecutionTargetListener { target ->
            project.xmakeBuildProfile(target)?.let(::refreshTargets)
        })
        targetConnection.subscribe(XMakeBuildProfileManager.TOPIC, XMakeBuildProfileManager.Listener {
            project.activeXMakeBuildProfile?.let(::refreshTargets)
        })
        dapDriverAutoDetect.addItemListener { event ->
            if (event.stateChange != ItemEvent.SELECTED && event.stateChange != ItemEvent.DESELECTED) {
                return@addItemListener
            }
            updateDapDriverPathState()
            if (!resetting && dapDriverAutoDetect.isSelected) {
                DapDriverDetector.findBestDriver()?.let { driver ->
                    replaceDefaultLaunchConfiguration(driver.type.displayName)
                }
            }
        }
    }

    override fun resetEditorFrom(configuration: XMakeRunConfiguration) {
        resetting = true
        try {
            selectTarget(configuration.runTarget)
            runArguments.text = configuration.runArguments
            environmentVariables.envData = configuration.runEnvironment
            dapDriverAutoDetect.isSelected = configuration.dapDriverAutoDetect
            dapDriverPath.text = configuration.dapDriverPath
            launchConfiguration.text = configuration.launchConfiguration.ifBlank {
                XMakeRunConfiguration.getDefaultLaunchConfigJson()
            }
            updateDapDriverPathState()
        } finally {
            resetting = false
        }
        val profile = project.activeXMakeBuildProfile
        if (profile == null) {
            clearTargetChoices(configuration.runTarget)
        } else {
            refreshTargets(profile)
        }
    }

    override fun applyEditorTo(configuration: XMakeRunConfiguration) {
        configuration.runTarget = targetModel.selectedItem?.toString() ?: DEFAULT_TARGET
        configuration.runArguments = runArguments.text
        configuration.runEnvironment = environmentVariables.envData
        configuration.dapDriverAutoDetect = dapDriverAutoDetect.isSelected
        configuration.dapDriverPath = dapDriverPath.text
        configuration.launchConfiguration = launchConfiguration.text
    }

    override fun createEditor(): JComponent = panel {
        row("Target:") {
            cell(targetComboBox).align(AlignX.FILL)
        }

        row("Program arguments:") {
            cell(runArguments).align(AlignX.FILL)
        }

        row("Environment variables:") {
            cell(environmentVariables.component).align(AlignX.FILL)
        }

        collapsibleGroup("Debug Configuration") {
            row {
                cell(dapDriverAutoDetect)
            }
            row("DAP driver:") {
                cell(dapDriverPath).align(AlignX.FILL)
            }
            row("Launch configuration:") {
                cell(scrollableLaunchConfiguration).align(AlignX.FILL)
            }
        }
    }

    override fun disposeEditor() {
        targetJob?.cancel()
        targetRequestId++
        targetConnection.disconnect()
        scope.cancel()
        super.disposeEditor()
    }

    private fun refreshTargets(profile: XMakeBuildProfile) {
        val requestedProfile = profile.copy()
        targetJob?.cancel()
        val requestId = ++targetRequestId
        val selectedTarget = targetModel.selectedItem?.toString() ?: DEFAULT_TARGET
        if (targetChoicesProfile != requestedProfile) {
            targetChoicesProfile = requestedProfile
            setTargetChoices(selectedTarget, emptyList())
        }

        targetJob = scope.launch {
            try {
                val targets = XMakeTargetManager.getInstance(project).loadTargets(requestedProfile)
                withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    if (
                        project.isDisposed ||
                        requestId != targetRequestId ||
                        targetChoicesProfile != requestedProfile
                    ) {
                        return@withContext
                    }
                    setTargetChoices(targetModel.selectedItem?.toString() ?: DEFAULT_TARGET, targets)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Keep the persisted target when profile discovery is unavailable.
                Log.warn("Failed to load XMake targets for profile ${requestedProfile.id}", error)
            }
        }
    }

    private fun clearTargetChoices(selected: String) {
        targetJob?.cancel()
        targetJob = null
        targetRequestId++
        targetChoicesProfile = null
        setTargetChoices(selected, emptyList())
    }

    private fun selectTarget(value: String) {
        val selectedTarget = value.ifBlank { DEFAULT_TARGET }
        if (targetModel.getIndexOf(selectedTarget) < 0) {
            targetModel.addElement(selectedTarget)
        }
        targetModel.selectedItem = selectedTarget
    }

    private fun setTargetChoices(selected: String, targets: Iterable<String>) {
        val selectedTarget = selected.ifBlank { DEFAULT_TARGET }
        val choices = buildList {
            add(DEFAULT_TARGET)
            addAll(targets.filter(String::isNotBlank))
            add(selectedTarget)
        }.distinct()

        targetModel.removeAllElements()
        targetModel.addAll(choices)
        targetModel.selectedItem = selectedTarget
        targetComboBox.notifyModelChanged()
    }

    private fun updateDapDriverPathState() {
        dapDriverPath.isEnabled = !dapDriverAutoDetect.isSelected
    }

    private fun replaceDefaultLaunchConfiguration(driverName: String) {
        val current = launchConfiguration.text.trim()
        val gdbDefault = XMakeRunConfiguration.getDefaultGdbLaunchConfigJson()
        val lldbDefault = XMakeRunConfiguration.getDefaultLldbLaunchConfigJson()
        if (current.isNotEmpty() && current != gdbDefault.trim() && current != lldbDefault.trim()) return

        launchConfiguration.text = if (driverName.contains("gdb", ignoreCase = true)) {
            gdbDefault
        } else {
            lldbDefault
        }
    }

    private companion object {
        val Log = logger<XMakeRunConfigurationEditor>()

        const val DEFAULT_TARGET = "default"
    }
}
