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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import io.xmake.project.directory.hasXMakeProjectDirectorySource
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.analysis.showAnalysisView
import io.xmake.run.tidy.runClangTidy

/**
 * The project-wide entry to the clang-tidy check, beside XMake's Run and Build: the same
 * run the analysis view's toolbar action starts, reachable from the XMake menu and the
 * editor and project view popups, where a check is wanted without hunting for the tool
 * window first. The findings land in the Analysis tab of the XMake window, which this
 * shows (without stealing focus) so the entry keeps its promise from any context.
 */
class CheckClangTidyAction : DumbAwareAction(
    "Run Clang-Tidy",
    "Run the Clang-Tidy check on the project (xmake check clang.tidy) and show the findings in the Analysis tab",
    AllIcons.Actions.Execute,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val project = event.project
        // Visible in every xmake project; one check at a time, queued or running - the
        // same state the analysis view's toolbar gates on.
        event.presentation.isEnabledAndVisible = project != null && project.hasXMakeProjectDirectorySource
        event.presentation.isEnabled = project == null || !AnalysisService.getInstance(project).isCheckActive
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project: Project = event.project ?: return
        runClangTidy(project)
        showAnalysisView(project)
    }
}
