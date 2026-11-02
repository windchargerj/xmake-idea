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

import com.intellij.execution.ExecutionException
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.xmake.project.console.XMakeConsole
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.analysis.ANALYSIS_TOOL_WINDOW_ID
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutor
import io.xmake.run.command.xmakeCommands
import io.xmake.run.state.XMakeRunState
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
    // The toolchains, not the plugin, decide availability; xmake force-adds the flags and
    // the compile would fail cryptically, so the runs that cannot work anywhere on this
    // host say so up front.
    val host = factory.toolkit.host
    if (host.type == ToolkitHostType.LOCAL && com.intellij.openapi.util.SystemInfo.isWindows &&
        kind in WINDOWS_UNSUPPORTED
    ) {
        throw ExecutionException(
            "${kind.displayName} needs Linux or macOS - run it through a WSL toolkit, " +
                    "or use AddressSanitizer under MSVC or clang",
        )
    }
    // A local run mirrors its reports into files the plugin reads back at the end: the
    // directory must exist before the target starts - current runtimes create it
    // themselves, older ones only fall back to stderr - and it must start empty, so a
    // debug launch's or a killed session's leftovers never pose as this run's findings.
    if (host.type == ToolkitHostType.LOCAL) {
        sanitizerLogDirectory(project)?.let { directory ->
            runCatching { Files.createDirectories(directory) }
                .onFailure { error -> Log.warn("Could not create the sanitizer log directory $directory", error) }
            clearSanitizerLogFiles(directory, kind)
        }
    }
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

/** The runtimes no Windows toolchain carries; AddressSanitizer runs under MSVC or clang. */
private val WINDOWS_UNSUPPORTED = setOf(SanitizerKind.MEMORY, SanitizerKind.THREAD, SanitizerKind.LEAK)

/**
 * The environment the sanitized target runs with: the configuration's own variables, plus
 * the runtime's option variable carrying the defaults - unless the user set their own,
 * which always win.
 *
 * The defaults also point the runtime's `log_path` at a file under the project root, so
 * the run leaves a complete report the plugin reads back once the target exits - the
 * stderr collection alone can garble findings (an interleaved console) or drop them
 * (its size cap). The mirror rides only while the run's build profile resolves a local
 * toolkit: a WSL or SSH run writes the file on its own filesystem, where the IDE could
 * never read it back, so those runs stay with the plain stderr collection. A `log_path`
 * of the user's own is never replaced.
 */
internal fun sanitizerEnvironment(configuration: XMakeRunConfiguration): EnvironmentVariablesData {
    val kind = configuration.sanitizer ?: return configuration.runEnvironment
    val project = configuration.project
    val userOptions = configuration.runEnvironment.envs[kind.optionVariable] ?: kind.defaultOptions
    // The runtime's options read one `key:value` list; the user's own keys win, and the
    // defaults fill only the keys their value does not carry.
    val merged = kind.defaultOptions
        .split(':')
        .filter { option -> !userOptions.contains("${option.substringBefore('=')}=") }
        .plus(userOptions)
        .joinToString(":")
        .ifEmpty { userOptions }
    val mirrorOption = mirrorLogPathOption(project, kind)
        ?.takeIf { !userOptions.contains(LOG_PATH_KEY) }
    val options = if (mirrorOption == null) merged else "$merged:$mirrorOption"
    return EnvironmentVariablesData.create(
        configuration.runEnvironment.envs + (kind.optionVariable to options),
        configuration.runEnvironment.isPassParentEnvs,
    )
}

/** The directory under the project root where the runtimes' report mirror files land. */
private const val SANITIZER_LOG_DIRECTORY = ".xmake-analysis"

/** The pid placeholder the runtimes expand into the file name the read matches back. */
private const val PID_PLACEHOLDER = "%p"

/** Marks a user's own log_path, which the mirrored default never overrides. */
private const val LOG_PATH_KEY = "log_path="

/** How much of the mirror files one run reads back; the stderr collector keeps the same
 *  budget, and for the same reason: a chatty target must not grow the IDE. */
private const val SANITIZER_LOG_READ_LIMIT = 2 * 1024 * 1024

/**
 * The `log_path` default of one sanitized local run: the runtime mirrors every report
 * into `<project root>/.xmake-analysis/<kind>.<pid>.log`. Null when the run's build
 * profile resolves no local toolkit or the project carries no base path - a WSL or SSH
 * run has no file the IDE could point at, and neither has the default project.
 */
private fun mirrorLogPathOption(project: Project, kind: SanitizerKind): String? {
    val mirrorsLocally = project.activeOrSingleXMakeBuildProfile
        ?.resolveToolkit(project)?.host?.type == ToolkitHostType.LOCAL
    if (!mirrorsLocally) return null
    val file = sanitizerLogDirectory(project) ?: return null
    return logPathOption(file.resolve("${kind.logPrefix()}.$PID_PLACEHOLDER.log"))
}

/**
 * The `log_path` option for [file]: the path forward-slashed and the whole value quoted,
 * because the options list splits on `:` and a Windows drive colon would otherwise cut
 * the path in two.
 */
internal fun logPathOption(file: Path): String =
    "log_path=\"${file.toString().replace('\\', '/')}\""

/** The mirror directory of [project], or null when the project has no base path. */
private fun sanitizerLogDirectory(project: Project): Path? =
    project.basePath?.let { root -> Path.of(root, SANITIZER_LOG_DIRECTORY) }

/** The file-name prefix of one sanitizer's mirror files: `address.1234.log` and friends. */
private fun SanitizerKind.logPrefix(): String = name.lowercase()

/** The mirror files of [kind] in [directory], name-sorted so the read stays deterministic. */
private fun sanitizerLogFiles(directory: Path, kind: SanitizerKind): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    val mirrorName = Regex("^${Regex.escape(kind.logPrefix())}\\.\\d+\\.log$")
    return try {
        Files.list(directory).use { files ->
            files.filter { file -> mirrorName.matches(file.fileName.toString()) }
                .sorted()
                .toList()
        }
    } catch (error: IOException) {
        Log.warn("Could not list the sanitizer log directory $directory", error)
        emptyList()
    }
}

/**
 * Reads the mirror files of one run back and deletes them, joined in name order. The
 * files hold what the stderr collection could lose - a report an interleaved console
 * garbled, or the tail its cap dropped - and deleting keeps the directory empty, so
 * nothing of a killed session leaks into a later run's report. One budget bounds the
 * read the way the stderr collector bounds its capture.
 */
internal fun readSanitizerLogFiles(directory: Path, kind: SanitizerKind): String {
    val collected = StringBuilder()
    var budget = SANITIZER_LOG_READ_LIMIT
    for (file in sanitizerLogFiles(directory, kind)) {
        val text = try {
            if (budget <= 0) null
            else Files.newInputStream(file).use { input ->
                val bytes = input.readNBytes(budget)
                budget -= bytes.size
                bytes.toString(Charsets.UTF_8)
            }
        } catch (error: IOException) {
            Log.warn("Could not read the sanitizer log file $file", error)
            null
        }
        if (text != null) {
            if (collected.isNotEmpty()) collected.append('\n')
            collected.append(text)
        }
        // Readable or not, the file served this run alone; it must not survive it.
        runCatching { Files.deleteIfExists(file) }
    }
    Log.info("Collected ${collected.length} character(s) of ${kind.displayName} log files")
    return collected.toString()
}

/** The run-wide entry the runner calls on a local host; other hosts keep stderr only. */
internal fun readSanitizerLogFiles(project: Project, kind: SanitizerKind): String =
    sanitizerLogDirectory(project)?.let { directory -> readSanitizerLogFiles(directory, kind) } ?: ""

/** Deletes [kind]'s mirror files ahead of a run, so the read at its end sees this run's
 *  files alone - not a debug launch's or a killed session's leftovers. */
private fun clearSanitizerLogFiles(directory: Path, kind: SanitizerKind) {
    sanitizerLogFiles(directory, kind).forEach { file -> runCatching { Files.deleteIfExists(file) } }
}

/**
 * Parses the collected output of one sanitized run into findings and hands them to the
 * analysis view. A run without findings still replaces the view's content - absence is
 * the result too - and both outcomes leave a line in the run console pointing at the view.
 * The mirror files' text joins the stderr inside the same parser: a runtime writes each
 * report to either the file or stderr, never both, and the parser's signature merge
 * keeps a report that somehow reached both channels counted once.
 */
internal fun publishAnalysisReport(
    project: Project,
    configuration: RunConfiguration,
    runCommand: XMakeCommand,
    kind: SanitizerKind,
    stderr: String,
    logFiles: String = "",
    console: XMakeConsole? = null,
) {
    val report = SanitizerReportParser(kind)
        .apply {
            feed(stderr)
            if (logFiles.isNotEmpty()) feed(logFiles)
        }
        .finish(configuration.name)
        .copy(toolkit = runCommand.toolkit, hostWorkingDirectory = runCommand.workingDirectory)
    invokeLater {
        if (project.isDisposed) return@invokeLater
        AnalysisService.getInstance(project).publish(report)
        console?.print(
            if (report.issues.isEmpty()) {
                "${kind.displayName}: no findings\n"
            } else {
                "${kind.displayName}: ${report.totalFindings} finding(s) at " +
                        "${report.issues.size} unique place(s) - see the XMake Analysis view\n"
            },
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        if (report.issues.isNotEmpty()) {
            ToolWindowManager.getInstance(project)
                .getToolWindow(ANALYSIS_TOOL_WINDOW_ID)
                ?.activate(null)
        } else {
            Log.info("Sanitizer run of '${configuration.name}' under ${kind.displayName} reported no findings")
        }
    }
}

/** Drops the findings of the previous run when a plain run starts. */
internal fun clearSanitizerFindings(project: Project) {
    invokeLater {
        if (!project.isDisposed) AnalysisService.getInstance(project).publish(null)
    }
}

/** Remembers the configuration a sanitized run used, for the view's rerun button. */
internal fun rememberSanitizedRun(project: Project, settings: com.intellij.execution.RunnerAndConfigurationSettings) {
    invokeLater {
        if (!project.isDisposed) AnalysisService.getInstance(project).rememberSanitizedSettings(settings)
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
