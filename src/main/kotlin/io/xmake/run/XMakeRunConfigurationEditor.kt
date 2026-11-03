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

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import io.xmake.debug.DapDriverDetector
import io.xmake.utils.ui.coroutineScope
import io.xmake.debug.DapDriverType
import io.xmake.project.directory.ui.DirectoryBrowser
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.sanitizer.SanitizerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val targetSelector = XMakeBuildTargetSelector(project, this)
    private val runArguments = RawCommandLineEditor()
    private val environmentVariables = EnvironmentVariablesComponent(project)
    private val launchWorkingDirectory = DirectoryBrowser(
        project,
        browseTitle = "Working Directory",
        browseDescription = "Select the target process working directory",
    )

    private val emulateTerminalInConsole = JBCheckBox("Emulate terminal in the output console")

    /** None plus one entry per sanitizer; the model keeps only what the active toolkit can run. */
    private val sanitizerKind = com.intellij.openapi.ui.ComboBox(
        arrayOf(NO_SANITIZER) + SanitizerKind.entries.map(SanitizerKind::displayName).toTypedArray(),
    ).apply { renderer = com.intellij.ui.SimpleListCellRenderer.create("") { it } }

    /** Rewrites the sanitizer row's comment; wired to the real line in [createEditor]. */
    private var sanitizerComment: (String) -> Unit = {}

    private val useDapDriver = JBCheckBox(
        "Use an external DAP driver instead of CLion's active debug profile",
    )
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
        preferredSize = launchConfigurationSize
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
        preferredSize = launchConfigurationSize
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    private var editedConfiguration: XMakeRunConfiguration? = null
    private var isResetting = false
    private val profileSelectionConnection = project.messageBus.connect(this)

    /** Auto-detection scans files and may run version detection, so it runs off the EDT. */
    private val detectScope = coroutineScope()
    private var detectJob: Job? = null

    init {
        profileSelectionConnection.subscribeToContextChanges {
            editedConfiguration?.let(::refreshWorkingDirectoryToolkit)
        }
        dapDriverAutoDetect.addItemListener { event ->
            if (event.stateChange != ItemEvent.SELECTED && event.stateChange != ItemEvent.DESELECTED) {
                return@addItemListener
            }
            updateDapControls()
            if (!isResetting && dapDriverAutoDetect.isSelected) {
                startDriverAutoDetection()
            }
        }
        useDapDriver.addItemListener { event ->
            if (event.stateChange != ItemEvent.SELECTED && event.stateChange != ItemEvent.DESELECTED) {
                return@addItemListener
            }
            updateDapControls()
        }
        Disposer.register(this) { detectScope.cancel() }
    }

    /** Cancelling the previous detection also invalidates its pending result, so a reset or a
     *  re-selection can never apply a stale driver default. */
    private fun startDriverAutoDetection() {
        detectJob?.cancel()
        detectJob = detectScope.launch {
            val driver = withContext(Dispatchers.IO) { DapDriverDetector.findBestDriver() }
            withContext(Dispatchers.EDT) {
                if (!isResetting && dapDriverAutoDetect.isSelected) {
                    driver?.let { found -> applyDefaultLaunchConfig(found.type) }
                }
            }
        }
    }

    private fun updateDapControls() {
        val dap = useDapDriver.isSelected
        dapDriverAutoDetect.isEnabled = dap
        dapDriverPath.isEnabled = dap && !dapDriverAutoDetect.isSelected
        scrollableLaunchConfiguration.isEnabled = dap
        launchConfiguration.isEnabled = dap
    }

    override fun resetEditorFrom(configuration: XMakeRunConfiguration) {
        detectJob?.cancel()
        editedConfiguration = configuration
        isResetting = true
        try {
            runArguments.text = configuration.runArguments
            launchWorkingDirectory.text = configuration.launchWorkingDirectory
            environmentVariables.envData = configuration.runEnvironment
            emulateTerminalInConsole.isSelected = configuration.emulateTerminalInConsole
            sanitizerKind.selectedItem = SanitizerKind.entries
                .firstOrNull { it.name == configuration.sanitizerKind }?.displayName ?: NO_SANITIZER
            useDapDriver.isSelected = configuration.useDapDriver
            dapDriverAutoDetect.isSelected = configuration.dapDriverAutoDetect
            dapDriverPath.text = configuration.dapDriverPath
            launchConfiguration.text = configuration.launchConfiguration.ifBlank {
                XMakeRunConfiguration.defaultLaunchConfigJson()
            }
            updateDapControls()
        } finally {
            isResetting = false
        }
        targetSelector.reset(configuration)
        refreshWorkingDirectoryToolkit(configuration)
    }

    override fun applyEditorTo(configuration: XMakeRunConfiguration) {
        configuration.runTarget = targetSelector.selectedTarget
        configuration.runArguments = runArguments.text
        configuration.launchWorkingDirectory = launchWorkingDirectory.text
        configuration.runEnvironment = environmentVariables.envData
        configuration.emulateTerminalInConsole = emulateTerminalInConsole.isSelected
        configuration.sanitizerKind = SanitizerKind.entries
            .firstOrNull { it.displayName == sanitizerKind.selectedItem }?.name.orEmpty()
        configuration.useDapDriver = useDapDriver.isSelected
        configuration.dapDriverAutoDetect = dapDriverAutoDetect.isSelected
        configuration.dapDriverPath = dapDriverPath.text
        configuration.launchConfiguration = launchConfiguration.text
    }

    override fun createEditor(): JComponent = panel {
        row("Target:") {
            cell(targetSelector.component).align(AlignX.FILL)
        }

        row("Program arguments:") {
            cell(runArguments).align(AlignX.FILL)
        }

        row("Working directory:") {
            cell(launchWorkingDirectory).align(AlignX.FILL)
        }

        row("Environment variables:") {
            cell(environmentVariables.component).align(AlignX.FILL)
        }

        row {
            cell(emulateTerminalInConsole)
        }

        row("Sanitizer:") {
            sanitizerComment = cell(sanitizerKind).mutableComment(SANITIZER_ROW_COMMENT)
        }

        collapsibleGroup("Debug Configuration") {
            row {
                cell(useDapDriver)
            }
            row {
                cell(dapDriverAutoDetect)
            }
            row("DAP driver:") {
                cell(dapDriverPath).align(AlignX.FILL)
            }
            row("DAP launch configuration:") {
                cell(scrollableLaunchConfiguration).align(AlignX.FILL)
            }
        }
    }

    /** Pushes the profile's resolved toolkit to the controls that depend on it. */
    private fun refreshWorkingDirectoryToolkit(configuration: XMakeRunConfiguration) {
        val toolkit = configuration.resolveBuildProfile(project)?.resolveToolkit(project)
        launchWorkingDirectory.setToolkit(toolkit)
        updateSanitizerAvailability(toolkit)
    }

    /**
     * Rebuilds the sanitizer combo for the active toolkit. A local Windows toolchain carries
     * no MSan/TSan/LSan runtime - the same set the run-time guard in SanitizerRun rejects -
     * so those entries leave the model instead of failing the build later, and the comment
     * says where to find them. An unresolved toolkit has no verdict, so the combo keeps
     * every entry; WSL and SSH toolkits and non-Windows hosts are not restricted either.
     *
     * When the rebuild drops the selection the configuration has stored, the comment names
     * it explicitly: apply writes back whatever the combo shows, so a silently reset row
     * would turn "OK" into rewriting a saved sanitizer into a plain run.
     */
    private fun updateSanitizerAvailability(toolkit: Toolkit?) {
        val windowsLocal = SystemInfo.isWindows && toolkit?.host?.type == ToolkitHostType.LOCAL
        val offered = if (windowsLocal) {
            SanitizerKind.entries - WINDOWS_LOCAL_UNSUPPORTED
        } else {
            SanitizerKind.entries
        }
        val items = (listOf(NO_SANITIZER) + offered.map(SanitizerKind::displayName)).toTypedArray()
        val selection = sanitizerKind.selectedItem as? String
        sanitizerKind.model = DefaultComboBoxModel(items)
        sanitizerKind.selectedItem = selection.takeIf(items::contains) ?: NO_SANITIZER
        val dropped = selection?.takeIf { it != NO_SANITIZER && !items.contains(it) }
        sanitizerComment(
            when {
                // Only the restricted rebuild can drop a selection; name the lost value so
                // the reset is a sentence on the row, not something OK does in passing.
                dropped != null ->
                    "$SANITIZER_ROW_COMMENT. The configuration's $dropped is unavailable on this toolkit " +
                            "and is reset to $NO_SANITIZER - applying now stores a plain run; switch to a WSL or " +
                            "SSH toolkit to keep it. $WINDOWS_LOCAL_SANITIZER_NOTE"
                windowsLocal -> "$SANITIZER_ROW_COMMENT. $WINDOWS_LOCAL_SANITIZER_NOTE"
                else -> SANITIZER_ROW_COMMENT
            },
        )
    }

    private fun applyDefaultLaunchConfig(driverType: DapDriverType) {
        val currentLaunchConfiguration = launchConfiguration.text.trim()
        val gdbDefault = XMakeRunConfiguration.defaultGdbLaunchConfigJson()
        val lldbDefault = XMakeRunConfiguration.defaultLldbLaunchConfigJson()
        if (
            currentLaunchConfiguration.isNotEmpty() &&
            currentLaunchConfiguration != gdbDefault.trim() &&
            currentLaunchConfiguration != lldbDefault.trim()
        ) return

        launchConfiguration.text = when (driverType) {
            DapDriverType.GDB -> gdbDefault
            DapDriverType.LLDB -> lldbDefault
        }
    }

    private companion object {
        val launchConfigurationSize = Dimension(400, 140)

        /** The sanitizer combo's plain-run entry. */
        const val NO_SANITIZER = "None"

        /** What the sanitizer row always explains: the policy that instruments the build, and where the findings land. */
        const val SANITIZER_ROW_COMMENT =
            "Rebuilds with the sanitizer's build.sanitizer.* policy and shows the findings in the XMake Analysis view"

        /** The note the row appends when the active toolkit cannot run every sanitizer. */
        const val WINDOWS_LOCAL_SANITIZER_NOTE =
            "MemorySanitizer, ThreadSanitizer and LeakSanitizer need Linux/macOS - use a WSL toolkit"

        /** Mirrors the run-time WINDOWS_UNSUPPORTED in SanitizerRun: the runtimes no Windows toolchain carries. */
        val WINDOWS_LOCAL_UNSUPPORTED = setOf(SanitizerKind.MEMORY, SanitizerKind.THREAD, SanitizerKind.LEAK)
    }
}

/** Adds a comment line under a cell whose text the editor can rewrite later. */
private fun Cell<*>.mutableComment(text: String): (String) -> Unit {
    comment(text)
    val line = comment!!
    return { value ->
        line.text = value
        line.parent?.revalidate()
    }
}
