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
package io.xmake.run.tidy

import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.analysis.ANALYSIS_TOOL_WINDOW_ID
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.command.XMakeCommandProcessHandler
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.requireXMakeBuildProfileFor
import io.xmake.utils.notify

/**
 * Runs `xmake check clang.tidy` over the project and hands its findings to the analysis
 * view. xmake builds the compile database the checker reads and may install clang-tidy
 * itself on first use, so the run can take a while; progress and the raw findings stay
 * visible in the XMake console while the parsed ones land in the view.
 */
internal fun runClangTidy(project: Project) {
    val executor = project.xmakeExecutor
    executor.submit {
        val console = XMakeConsoleManager.getInstance(project).awaitReady()
        console.clear()
        console.print("clang-tidy: checking the project\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val target = ExecutionTargetManager.getActiveTarget(project)
        val profile = try {
            project.requireXMakeBuildProfileFor(target)
        } catch (error: Exception) {
            project.notify("Select an XMake build profile before running clang-tidy")
            return@submit
        }
        // Without a project .clang-tidy some builds enable no checks at all; the compiler
        // diagnostics and the analyzer checks are the neutral baseline then.
        val hasProjectConfig = java.io.File(project.basePath, ".clang-tidy").isFile
        val command = project.xmakeCommands(profile).createClangTidyCheck(
            checks = if (hasProjectConfig) null else DEFAULT_CHECKS,
        )
        if (command.toolkit.host.type == ToolkitHostType.LOCAL && findLocalClangTidy() == null) {
            console.print(
                "clang-tidy was not found on the toolkit's PATH; install it (your package " +
                        "manager, or xrepo install llvm) and run the check again - xmake's own " +
                        "auto-install prompts interactively and cannot run here\n",
                ConsoleViewContentType.ERROR_OUTPUT,
            )
            project.notify("Install clang-tidy on the PATH to run the check")
            return@submit
        }
        // The check builds the compile database and may install clang-tidy first - a
        // query's five-minute budget is not it. The process runs like a build: findings
        // stream into the console as they appear, and the same text feeds the parser.
        val findings = StringBuilder()
        var failed: Exception? = null
        try {
            XMakeCommandProcessHandler(
                project,
                command,
                XMakeConsoleOptions(showConsole = true),
                onTextAvailable = { text, outputType ->
                    console.print(text, ConsoleViewContentType.getConsoleViewType(outputType))
                    findings.append(text)
                },
            ).awaitSuccessfulCompletion()
        } catch (error: Exception) {
            failed = error
            Log.warn("clang-tidy check failed", error)
        }
        // The findings printed before a hard error are still findings; they land in the
        // view next to the failure note.
        val report = ClangTidyReportParser()
            .apply { feed(findings.toString()) }
            .finish(project.name ?: "project")
            .copy(toolkit = command.toolkit, hostWorkingDirectory = command.workingDirectory)
        invokeLater {
            if (project.isDisposed) return@invokeLater
            AnalysisService.getInstance(project).publish(report)
            failed?.let { error ->
                console.print("clang-tidy: check failed: ${error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                project.notify("clang-tidy failed: ${error.message}")
            }
            console.print(
                if (report.issues.isEmpty()) {
                    "clang-tidy: no findings\n"
                } else {
                    "clang-tidy: ${report.totalFindings} finding(s) at " +
                            "${report.issues.size} unique place(s) - see the XMake Analysis view\n"
                },
                ConsoleViewContentType.SYSTEM_OUTPUT,
            )
            if (report.issues.isNotEmpty()) {
                ToolWindowManager.getInstance(project)
                    .getToolWindow(ANALYSIS_TOOL_WINDOW_ID)
                    ?.activate(null)
            }
        }
    }
}

/** clang-tidy on the local PATH, or null; the toolkit's other hosts search their own. */
private fun findLocalClangTidy(): String? {
    val executable = if (com.intellij.openapi.util.SystemInfo.isWindows) "clang-tidy.exe" else "clang-tidy"
    return com.intellij.util.EnvironmentUtil.getValue("PATH")
        ?.split(java.io.File.pathSeparatorChar)
        ?.asSequence()
        ?.map { directory -> java.io.File(directory, executable) }
        ?.firstOrNull(java.io.File::isFile)
        ?.path
}

/** The checks that always exist: the compiler's own diagnostics and clang's analyzer. */
private const val DEFAULT_CHECKS = "clang-diagnostic-*,clang-analyzer-*"

private val Log = Logger.getInstance("io.xmake.run.tidy.ClangTidyRun")
