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

import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.xmake.project.console.XMakeConsole
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutor
import io.xmake.run.command.xmakeCommands
import io.xmake.run.state.XMakeRunState
import io.xmake.run.target.requireXMakeBuildProfileFor

/** The sanitizer a configuration selected, or null for a plain run. */
internal val XMakeRunConfiguration.sanitizer: SanitizerKind?
    get() = sanitizerKind.takeIf(String::isNotEmpty)
        ?.let { selected -> SanitizerKind.entries.firstOrNull { it.name == selected } }

/**
 * Rebuilds the target with the sanitizer's instrumentation before the run starts: one
 * configure with `-m debug` plus the sanitizer's `build.sanitizer.*` policy, and one
 * target build - the same two commands the other analysis runs prepare with, so the
 * profile the user edits keeps its own mode. The policy route works on every project;
 * the `mode.asan`-style rules only fire when the project attached them.
 */
internal suspend fun prepareSanitizerRun(
    executor: XMakeExecutor,
    state: XMakeRunState,
    environment: ExecutionEnvironment,
    console: XMakeConsole,
    kind: SanitizerKind,
) {
    val project = state.configuration.project
    val profile = project.requireXMakeBuildProfileFor(environment.executionTarget)
    val factory = project.xmakeCommands(profile)
    executor.execute(
        console,
        factory.createConfigure(modeOverride = DEBUG_MODE, policies = mapOf(kind.policy to "y")),
        XMakeConsoleOptions(showConsole = false, showOnFailure = true),
    )
    executor.execute(
        console,
        factory.createTargetBuild(state.configuration.runTarget),
        XMakeConsoleOptions(showConsole = false, showOnFailure = true, showProblems = true),
    )
}

/** The sanitizer builds keep full debug information and stay unoptimized: accurate stacks. */
private const val DEBUG_MODE = "debug"

/**
 * The environment the sanitized target runs with: the configuration's own variables, plus
 * the runtime's option variable carrying the defaults - unless the user set their own,
 * which always win.
 */
internal fun sanitizerEnvironment(configuration: XMakeRunConfiguration): EnvironmentVariablesData {
    val kind = configuration.sanitizer ?: return configuration.runEnvironment
    if (kind.optionVariable in configuration.runEnvironment.envs) return configuration.runEnvironment
    return EnvironmentVariablesData.create(
        configuration.runEnvironment.envs + (kind.optionVariable to kind.defaultOptions),
        configuration.runEnvironment.isPassParentEnvs,
    )
}

/**
 * Parses the collected stderr of one sanitized run into findings and hands them to the
 * analysis view. A run without findings still replaces the view's content - absence is
 * the result too - and the tool window comes forward only when there is something to see.
 */
internal fun publishSanitizerReport(
    project: Project,
    configuration: RunConfiguration,
    runCommand: XMakeCommand,
    kind: SanitizerKind,
    stderr: String,
) {
    val report = SanitizerReportParser(kind)
        .apply { feed(stderr) }
        .finish(configuration.name)
        .copy(toolkit = runCommand.toolkit, hostWorkingDirectory = runCommand.workingDirectory)
    invokeLater {
        if (project.isDisposed) return@invokeLater
        SanitizerAnalysisService.getInstance(project).publish(report)
        if (report.issues.isNotEmpty()) {
            ToolWindowManager.getInstance(project)
                .getToolWindow(SANITIZER_TOOL_WINDOW_ID)
                ?.activate(null)
        } else {
            Log.info("Sanitizer run of '${configuration.name}' under ${kind.displayName} reported no findings")
        }
    }
}

/** Drops the findings of the previous run when a plain run starts. */
internal fun clearSanitizerFindings(project: Project) {
    invokeLater {
        if (!project.isDisposed) SanitizerAnalysisService.getInstance(project).publish(null)
    }
}

/** Remembers the configuration a sanitized run used, for the view's rerun button. */
internal fun rememberSanitizedRun(project: Project, settings: com.intellij.execution.RunnerAndConfigurationSettings) {
    invokeLater {
        if (!project.isDisposed) SanitizerAnalysisService.getInstance(project).rememberSanitizedSettings(settings)
    }
}

/** Collects the stderr of one run, bounded so a chatty target cannot grow the IDE. */
internal class SanitizerOutputCollector(private val kind: SanitizerKind) {

    private val output = StringBuilder()
    private var truncated = false

    fun append(text: String) {
        if (output.length >= LIMIT) {
            truncated = true
            return
        }
        output.append(text)
    }

    /** The collected stderr, with a note where the cap cut it off. */
    fun stderr(): String = buildString {
        append(output)
        if (truncated) {
            append("\n[analysis] output cap reached (")
            append(LIMIT / 1024)
            append(" KB); findings after this point were dropped\n")
        }
    }.also {
        Log.info("Collected ${it.length} character(s) of ${kind.displayName} stderr")
    }

    private companion object {
        private const val LIMIT = 2 * 1024 * 1024
    }
}

private val Log = Logger.getInstance("io.xmake.run.sanitizer.SanitizerRun")
