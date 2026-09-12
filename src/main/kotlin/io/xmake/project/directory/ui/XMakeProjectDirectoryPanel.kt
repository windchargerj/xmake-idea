package io.xmake.project.directory.ui

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import io.xmake.project.directory.HostDirectory
import io.xmake.project.directory.validateAbsoluteHostDirectory
import io.xmake.project.directory.XMakeProjectDirectoryState
import io.xmake.project.directory.validateLocalDirectory
import io.xmake.project.directory.xmakeProjectDirectories
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.project.toolkit.ToolkitManager
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class XMakeProjectDirectoryPanel(private val project: Project) {
    private var baseline = project.xmakeProjectDirectories.getState()

    private val localDirectoryBrowser = DirectoryBrowser(
        project,
        browseTitle = "XMake Project Directory",
        browseDescription = "Select the directory containing the root xmake.lua",
    ).apply {
        setLocal()
    }

    private val hostEditorsContainer = JPanel(BorderLayout())
    private var hostEditors = createHostEditors()

    val focusableComponent: JComponent
        get() = localDirectoryBrowser

    init {
        rebuildHostEditors()
    }

    /**
     * Adds the directory rows to [panel] so they share the Settings page grid with
     * the other labeled fields instead of using a separately-sized sub-panel.
     */
    fun attachTo(panel: Panel) {
        panel.row("Local directory:") {
            cell(localDirectoryBrowser)
                .align(AlignX.FILL)
                .comment("Directory containing root xmake.lua. Empty uses the IDE project root.")
        }
        panel.row {
            cell(hostEditorsContainer).align(AlignX.FILL)
        }
    }

    fun refreshToolkits() {
        val editedDirectories = hostEditors.associate { editor ->
            editor.hostId to editor.directory.text
        }
        hostEditors = createHostEditors().map { editor ->
            editor.directory.text = editedDirectories[editor.hostId]
                ?: baseline.hostDirectories
                    .firstOrNull { it.hostId == editor.hostId }
                    ?.directory
                    .orEmpty()
            editor
        }
        rebuildHostEditors()
    }

    private fun createHostEditors(): List<HostDirectoryEditor> =
        ToolkitManager.getInstance()
            .registeredToolkits(project)
            .distinctBy { toolkit -> toolkit.host.id.canonical }
            .filter { toolkit -> toolkit.host.type != ToolkitHostType.LOCAL }
            .map { toolkit -> HostDirectoryEditor(project, toolkit) }

    private fun rebuildHostEditors() {
        hostEditorsContainer.removeAll()
        if (hostEditors.isEmpty()) {
            hostEditorsContainer.isVisible = false
        } else {
            val hostPanel = panel {
                hostEditors.forEach { editor ->
                    row("${editor.displayName}:") {
                        cell(editor.directory).align(AlignX.FILL)
                    }
                }
                row {
                    comment(
                        "For WSL, empty resolves from the local project directory. SSH requires an absolute host path.",
                    )
                }
            }
            hostEditorsContainer.add(hostPanel, BorderLayout.CENTER)
            hostEditorsContainer.isVisible = true
        }
        hostEditorsContainer.revalidate()
        hostEditorsContainer.repaint()
    }

    fun reset() {
        baseline = project.xmakeProjectDirectories.getState()
        localDirectoryBrowser.text = baseline.localDirectory
        hostEditors.forEach { editor ->
            editor.directory.text = baseline.hostDirectories
                .firstOrNull { it.hostId == editor.hostId }
                ?.directory.orEmpty()
        }
    }

    val isModified: Boolean
        get() = currentState() != baseline

    @Throws(ConfigurationException::class)
    fun apply() {
        val state = currentState()
        if (state.localDirectory.isNotBlank()) {
            requireValidLocalDirectory(state.localDirectory)
        }
        state.hostDirectories.forEach { hostDirectory ->
            try {
                validateAbsoluteHostDirectory(hostDirectory.directory)
            } catch (error: RuntimeConfigurationError) {
                throw ConfigurationException(
                    "XMake ${hostDirectory.hostId} ${error.localizedMessage}",
                )
            }
        }
        project.xmakeProjectDirectories.replaceState(state)
        baseline = state.copyState()
    }

    private fun currentState(): XMakeProjectDirectoryState {
        val editedHosts = hostEditors.mapNotNull { editor ->
            editor.directory.text.trim()
                .takeUnless(String::isBlank)
                ?.let { directory -> HostDirectory(editor.hostId, directory) }
        }
        val editorHostIds = hostEditors.mapTo(mutableSetOf()) { it.hostId }
        // Keep entries for hosts that are not currently registered (an offline WSL distro or a
        // removed toolkit): dropping them would silently erase the configured directory.
        val hiddenHosts = baseline.hostDirectories.filter { it.hostId !in editorHostIds }
        return XMakeProjectDirectoryState(
            localDirectory = localDirectoryBrowser.text.trim(),
            hostDirectories = (editedHosts + hiddenHosts).toMutableList(),
        )
    }

    private fun requireValidLocalDirectory(directory: String) {
        try {
            validateLocalDirectory(project, directory)
        } catch (error: RuntimeConfigurationError) {
            throw ConfigurationException(error.localizedMessage)
        }
    }

    private class HostDirectoryEditor(
        project: Project,
        toolkit: Toolkit,
    ) {
        val hostId = toolkit.host.id.canonical
        val displayName = "${toolkit.host.type}: ${toolkit.host.displayName}"
        val directory = DirectoryBrowser(
            project,
            browseTitle = "XMake Project Directory",
            browseDescription = "Select the project directory on ${toolkit.host.displayName}",
        ).apply {
            setToolkit(toolkit)
        }
    }

}
