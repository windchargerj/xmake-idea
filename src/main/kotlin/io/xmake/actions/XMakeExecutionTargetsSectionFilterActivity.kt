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
package io.xmake.actions

import com.intellij.execution.RunManager
import com.intellij.execution.actions.EXECUTION_TARGETS_COMBO_ACTION_PLACE
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.xmake.run.XMakeRunConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps CLion's CMake entries out of the execution-target popup while it selects an XMake profile. */
class XMakeExecutionTargetsSectionFilterActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) { installExecutionTargetsSectionFilter() }
    }
}

private const val TARGETS_SECTION_GROUP_ID = "ExecutionTargets.Additional"
private const val CMAKE_SECTION_GROUP_ID = "CMake.ExecutionTargets.Additional"

private val sectionFilterLock = Any()
private var sectionFilterInstalled = false

/**
 * CLion appends its "Edit CMake Profiles..." section to the shared execution-target popup
 * whenever the project has CMake initialized, without checking which run configuration is
 * selected. The wrapper hides that section while an XMake run configuration owns the popup,
 * mirroring how [EditXMakeBuildProfilesAction] hides itself for non-XMake selections.
 */
internal fun installExecutionTargetsSectionFilter() {
    synchronized(sectionFilterLock) {
        if (sectionFilterInstalled) return

        val actionManager = ActionManager.getInstance()
        val sectionParent = actionManager.getAction(TARGETS_SECTION_GROUP_ID) as? DefaultActionGroup
            ?: return
        val cmakeSection = actionManager.getAction(CMAKE_SECTION_GROUP_ID) ?: return
        if (sectionParent.getChildren(null).none { child -> child === cmakeSection }) return

        sectionParent.remove(cmakeSection)
        sectionParent.add(XMakeAwareSectionGroup(cmakeSection))
        sectionFilterInstalled = true
        Log.info("Wrapped $CMAKE_SECTION_GROUP_ID in the execution-target popup")
    }
}

/** Renders the wrapped section, but not for the execution-target popup of an XMake configuration. */
private class XMakeAwareSectionGroup(section: AnAction) : DefaultActionGroup(section) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        if (event.place != EXECUTION_TARGETS_COMBO_ACTION_PLACE) return
        val project = event.project ?: return
        val selectedConfiguration = RunManager.getInstance(project).selectedConfiguration?.configuration
        event.presentation.isVisible = selectedConfiguration !is XMakeRunConfiguration
    }
}

private val Log = logger<XMakeExecutionTargetsSectionFilterActivity>()
