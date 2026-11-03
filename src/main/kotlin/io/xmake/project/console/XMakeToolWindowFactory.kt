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
 * @file        XMakeToolWindowFactory.kt
 *
 */
package io.xmake.project.console

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

class XMakeToolWindowFactory : ToolWindowFactory, DumbAware {

    @Deprecated("Deprecated in ToolWindowFactory", ReplaceWith("isApplicableAsync(project)"))
    override fun isApplicable(project: Project): Boolean = true
    override suspend fun isApplicableAsync(project: Project): Boolean = true

    @Deprecated("Deprecated in ToolWindowFactory")
    override val isDoNotActivateOnStart: Boolean = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val outputPanel = XMakeToolWindowOutputPanel(project)
        val outputTab = ContentFactory.getInstance().createContent(outputPanel, "Output", false)
        outputTab.setDisposer(outputPanel)
        toolWindow.contentManager.addContent(outputTab)

        val servicesPanel = XMakeServicesPanel(project)
        val servicesTab = ContentFactory.getInstance().createContent(servicesPanel, "Services", false)
        servicesTab.setDisposer(servicesPanel)
        toolWindow.contentManager.addContent(servicesTab)

        toolWindow.contentManager.setSelectedContent(outputTab)
        XMakeConsoleManager.getInstance(project).register(toolWindow, outputPanel, outputTab)

        // The services state is snapshot-only: a probe runs when the tab becomes the
        // selected one and on its Refresh action, never in the background. The listener
        // dies with the panel, which the tab disposes.
        val contentManager = toolWindow.contentManager
        val activationListener = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content === servicesTab) {
                    servicesPanel.refresh()
                }
            }
        }
        contentManager.addContentManagerListener(activationListener)
        Disposer.register(servicesPanel, Disposable { contentManager.removeContentManagerListener(activationListener) })
    }
}

const val XMAKE_TOOL_WINDOW_ID = "XMake"
