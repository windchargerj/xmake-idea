package io.xmake.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.xmake.shared.xmakeConfiguration
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.DefaultComboBoxModel

class XMakeRunConfigurationEditor(private val project: Project) : SettingsEditor<XMakeRunConfiguration>() {

    // the xmake configuration
    val xmakeConfiguration = project.xmakeConfiguration

    // the targets ui
    private val targetsModel = DefaultComboBoxModel<String>()
    private val targetsComboBox = ComboBox<String>(targetsModel)

    // the run arguments
    private val runArguments = RawCommandLineEditor()

    // the environment variables
    private val environmentVariables = EnvironmentVariablesComponent()

    // reset editor from configuration
    override fun resetEditorFrom(configuration: XMakeRunConfiguration) {

        // reset targets
        targetsModel.removeAllElements()
        for (target in xmakeConfiguration.targets) {
            targetsModel.addElement(target)
        }
        targetsModel.selectedItem = configuration.runTarget

        // reset run arguments
        runArguments.text = configuration.runArguments

        // reset environment variables
        environmentVariables.envData = configuration.runEnvironment
    }

    // apply editor to configuration
    override fun applyEditorTo(configuration: XMakeRunConfiguration) {

        configuration.runTarget         = targetsModel.selectedItem.toString()
        configuration.runArguments      = runArguments.text
        configuration.runEnvironment    = environmentVariables.envData
    }

    // create editor
    override fun createEditor(): JComponent = panel {
        row("Default target:") {
            cell(targetsComboBox).align(AlignX.FILL)
        }

        row("Program arguments:") {
            cell(runArguments).align(AlignX.FILL)
        }
        row(environmentVariables.label) {
            cell(environmentVariables).align(AlignX.FILL)
        }
    }

    private fun JPanel.makeWide() {
        preferredSize = Dimension(1000, height)
    }

    companion object {

        // get log
        private val Log = Logger.getInstance(XMakeRunConfigurationEditor::class.java.getName())
    }
}
