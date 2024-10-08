package io.xmake.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.layout.ComboBoxPredicate
import io.xmake.project.directory.ui.DirectoryBrowser
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ui.ToolkitComboBox
import io.xmake.project.toolkit.ui.ToolkitListItem
import javax.swing.DefaultComboBoxModel

@Deprecated("Please refer to the relevant content in folder io/xmake/project/wizard.")
class XMakeNewProjectPanel : Disposable {

    // the module kinds
    private val kindsModel = DefaultComboBoxModel<String>().apply {
        addElement("Console")
        addElement("Static Library")
        addElement("Shared Library")
    }

    // the module languages
    private val languagesModel = DefaultComboBoxModel<String>().apply {
        addElement("C")
        addElement("C++")
        addElement("Rust")
        addElement("Dlang")
        addElement("Go")
        addElement("Swift")
        addElement("Objc")
        addElement("Objc++")
    }

    private var toolkit: Toolkit? = null
    private val toolkitComboBox = ToolkitComboBox(::toolkit)

    private val browser = DirectoryBrowser(ProjectManager.getInstance().defaultProject)

    val data: XMakeConfigData
        get() = XMakeConfigData(
            languagesModel.selectedItem.toString().lowercase(),
            template,
            toolkit,
            browser.text
        ).also { println("XMakeConfigData: ${it.toolkit}, ${it.remotePath}") }

    // get template
    private val template: String
        get() = when (kindsModel.selectedItem.toString()) {
            "Console" -> "console"
            "Static Library" -> "static"
            "Shared Library" -> "shared"
            else -> "console"
        }

    fun attachTo(layout: Panel) = with(layout) {
        row("Remote Project Dir:") {
            cell(browser).align(AlignX.FILL)
        }.visibleIf(ComboBoxPredicate<ToolkitListItem>(toolkitComboBox) {
            (it as? ToolkitListItem.ToolkitItem)?.toolkit?.isOnRemote ?: false
        })
        row("Xmake Toolkit:") {
            cell(toolkitComboBox)
                .applyToComponent {
                    addToolkitChangedListener { toolkit ->
                        browser.removeBrowserAllListener()
                        toolkit?.let {
                            browser.addBrowserListenerByToolkit(it)
                        }
                    }
                    activatedToolkit?.let { browser.addBrowserListenerByToolkit(it) }
                }
                .align(AlignX.FILL)
        }
        row("Module Language:") {
            comboBox(languagesModel).align(AlignX.FILL)
        }
        row("Module Type:") {
            comboBox(kindsModel).align(AlignX.FILL)
        }
    }

    override fun dispose() {}

    companion object {
        val Log = logger<XMakeNewProjectPanel>()
    }

}