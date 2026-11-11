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
package io.xmake.run.packages.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The XMake Packages tool window: the project's xrepo packages and the repositories they
 * resolve against, as two tabs of one window - the declared packages and the search
 * surface first, the repository list beside it. A tab, not a mode, because the way back
 * must never depend on remembering which button brought the page in.
 *
 * It needs no CLion API - only `xmake require` and `xmake repo` queries through the
 * executor - so it lives in the main module beside the Analysis view it mirrors.
 */
internal class XMakePackagesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager

        val packagesContent = ContentFactory.getInstance().createContent(null, "Packages", false)
        val packagesPanel = XMakePackagesPanel(project, packagesContent)
        packagesContent.component = packagesPanel
        packagesContent.setDisposer(packagesPanel)
        contentManager.addContent(packagesContent)

        // The repositories page: created after the packages page, whose init issues the
        // first refresh both pages render from.
        val repositoriesContent = ContentFactory.getInstance().createContent(null, "Repositories", false)
        val repositoriesPanel = XMakeRepositoriesPanel(project, repositoriesContent)
        repositoriesContent.component = repositoriesPanel
        repositoriesContent.setDisposer(repositoriesPanel)
        contentManager.addContent(repositoriesContent)
    }
}
