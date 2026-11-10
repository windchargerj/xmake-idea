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
package io.xmake.run.sanitizer

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import io.xmake.run.XMakeRunConfiguration

/**
 * The Run menu's way into a sanitized run: the selected XMake configuration turns into a
 * sanitizer configuration of the picked kind and starts immediately.
 *
 * The sanitizer lives on a configuration type of its own, so the entry has to live outside
 * the plain configuration's editor - otherwise the one place a user looks for "run it under
 * a sanitizer" (the Run menu) would stay empty. The generated configuration persists: the
 * next run is one click in the run widget, and its kind stays editable in the editor.
 */
internal class RunWithSanitizerGroup : DefaultActionGroup("Run with Sanitizer", true) {

    init {
        templatePresentation.description =
            "Instrument the selected XMake configuration with a sanitizer and run it"
        SanitizerKind.entries.forEach { kind ->
            add(object : DumbAwareAction(kind.displayName) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabledAndVisible = resolveBase(event.project) != null
                }

                override fun actionPerformed(event: AnActionEvent) {
                    runWithSanitizer(event.project ?: return, kind)
                }
            })
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        // Only a plain XMake configuration has what the copy needs; elsewhere the entry
        // steps aside instead of offering an action that would do nothing.
        event.presentation.isEnabledAndVisible = resolveBase(event.project) != null
    }
}

/** A plain XMake configuration and the name the copy derives from it. */
private class BaseConfiguration(
    val settings: RunnerAndConfigurationSettings,
    val configuration: XMakeRunConfiguration,
)

/**
 * The configuration the action starts from: the run widget's selection, or - on a fresh
 * project whose widget holds no selection yet - the project's only plain XMake
 * configuration, which is unambiguous then. A sanitizer configuration is never a base.
 */
private fun resolveBase(project: Project?): BaseConfiguration? {
    val manager = project?.let { RunManager.getInstance(it) } ?: return null
    val selected = manager.selectedConfiguration
    val selectedBase = selected
        ?.takeIf { settings -> (settings.configuration as? XMakeRunConfiguration)?.type !is XMakeSanitizerRunConfigurationType }
    val settings = selectedBase ?: manager.allSettings
        .filter { candidate -> candidate.configuration is XMakeRunConfiguration }
        .filter { candidate ->
            (candidate.configuration as XMakeRunConfiguration).type !is XMakeSanitizerRunConfigurationType
        }
        .singleOrNull()
        ?: return null
    val configuration = settings.configuration as? XMakeRunConfiguration ?: return null
    return BaseConfiguration(settings, configuration)
}

/**
 * Creates (or refreshes) a sanitizer configuration from the base and runs it. The name is
 * derived from the source configuration, so running the same configuration again updates
 * its sanitizer twin instead of piling up copies.
 */
private fun runWithSanitizer(project: Project, kind: SanitizerKind) {
    val base = resolveBase(project) ?: return
    val manager = RunManager.getInstance(project)
    val type = ConfigurationTypeUtil.findConfigurationType(XMakeSanitizerRunConfigurationType::class.java)
    val factory = type.configurationFactories.firstOrNull() ?: return
    val name = "Sanitize ${base.settings.name}"
    // The action reflects the current selection: a stale twin of the same name is dropped
    // first, so the copy can never lag behind the configuration the user just changed.
    manager.allSettings
        .firstOrNull { settings -> settings.name == name && settings.type === type }
        ?.let { stale -> manager.removeConfiguration(stale) }
    val settings = manager.createConfiguration(name, factory)
    // createConfiguration's contract has differed across platform generations about
    // registering the result; adding only when absent keeps both worlds correct.
    if (manager.allSettings.none { candidate -> candidate === settings }) {
        manager.addConfiguration(settings)
    }
    val sanitizer = settings.configuration as? XMakeRunConfiguration ?: return
    sanitizer.runTarget = base.configuration.runTarget
    sanitizer.runArguments = base.configuration.runArguments
    sanitizer.launchWorkingDirectory = base.configuration.launchWorkingDirectory
    sanitizer.runEnvironment = base.configuration.runEnvironment
    sanitizer.emulateTerminalInConsole = base.configuration.emulateTerminalInConsole
    sanitizer.preferredBuildProfileId = base.configuration.preferredBuildProfileId
    sanitizer.sanitizerKind = kind.name
    manager.selectedConfiguration = settings
    ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
