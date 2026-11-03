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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.command.XMakeCommandProcessHandler
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.notifyMissingBuildProfile
import io.xmake.run.target.requireXMakeBuildProfileFor
import io.xmake.utils.notify
import kotlinx.coroutines.CancellationException

/**
 * Runs `xmake check clang.tidy` over the project and hands its findings to the analysis
 * view. xmake builds the compile database the checker reads and may install clang-tidy
 * itself on first use, so the run can take a while; progress and the raw findings stay
 * visible in the XMake console while the parsed ones land in the view.
 *
 * One check at a time, claimed at the click: the executor's single lane may sit behind a
 * long build, so a claim taken only once the task starts would let every click during the
 * wait queue another full `xmake check`. The view's stop button can cancel a claimed check
 * still queued on the lane, and kills the process of one already running.
 */
internal fun runClangTidy(project: Project) {
    val service = AnalysisService.getInstance(project)
    if (!service.beginCheck()) return
    val executor = project.xmakeExecutor
    service.registerCheckJob(
        executor.submit {
            val console = XMakeConsoleManager.getInstance(project).awaitReady()
            console.clear()
            console.print("clang-tidy: checking the project\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            val target = ExecutionTargetManager.getActiveTarget(project)
            val profile = try {
                project.requireXMakeBuildProfileFor(target)
            } catch (error: Exception) {
                service.endCheck()
                project.notifyMissingBuildProfile()
                return@submit
            }
            // Without a project .clang-tidy some builds enable no checks at all; the compiler
            // diagnostics and the analyzer checks are the neutral baseline then.
            val hasProjectConfig = java.io.File(project.basePath, ".clang-tidy").isFile
            val command = project.xmakeCommands(profile).createClangTidyCheck(
                checks = if (hasProjectConfig) null else DEFAULT_CHECKS,
            )
            if (command.toolkit.host.type == ToolkitHostType.LOCAL && findLocalClangTidy() == null) {
                service.endCheck()
                console.print(
                    "clang-tidy was not found on the toolkit's PATH; install it (your package " +
                            "manager, or xrepo install llvm) and run the check again - xmake's own " +
                            "auto-install prompts interactively and cannot run here\n",
                    ConsoleViewContentType.ERROR_OUTPUT,
                )
                project.notify(
                    "clang-tidy is not on the PATH",
                    "Install clang-tidy (your package manager, or xrepo install llvm) and run the check again.",
                )
                return@submit
            }
            // The check builds the compile database and may install clang-tidy first - a
            // query's five-minute budget is not it. The process runs like a build: findings
            // stream into the console as they appear, and the same text feeds the parser.
            val findings = StringBuilder()
            var failed: Exception? = null
            var registered = false
            try {
                val handler = XMakeCommandProcessHandler(
                    project,
                    command,
                    XMakeConsoleOptions(showConsole = true),
                    onTextAvailable = { text, outputType ->
                        console.print(text, ConsoleViewContentType.getConsoleViewType(outputType))
                        findings.append(text)
                    },
                )
                // The analysis view's stop button kills the running check through this
                // registration; it clears itself when the process ends, however it ends,
                // and takes over from the queued claim the click took.
                service.registerCheckProcess(handler.processHandler)
                registered = true
                handler.awaitSuccessfulCompletion()
            } catch (cancellation: CancellationException) {
                // The stop button stopped this check; the claim is already gone - released
                // by the registration, or by the stop itself while still queued.
                throw cancellation
            } catch (error: Exception) {
                if (!registered) service.endCheck()
                failed = error
                Log.warn("clang-tidy check failed", error)
            }
            // The findings printed before a hard error are still findings; they land in the
            // view with the failure named in the summary. A failure with nothing parsed
            // publishes nothing, though: an all-zero report would read as a clean check and
            // silently replace the report the user is looking at - it stays console and
            // balloon only, and the previous report stays current.
            var report = ClangTidyReportParser()
                .apply { feed(findings.toString()) }
                .finish(project.name ?: "project")
                .copy(toolkit = command.toolkit, hostWorkingDirectory = command.workingDirectory)
            failed?.let { error ->
                report = report.copy(failure = error.message ?: error.javaClass.simpleName)
            }
            failed?.let { error ->
                val reason = error.message ?: error.javaClass.simpleName
                console.print("clang-tidy: check failed: $reason\n", ConsoleViewContentType.ERROR_OUTPUT)
                project.notify("clang-tidy check failed", StringUtil.escapeXmlEntities(reason))
            }
            if (failed == null || report.issues.isNotEmpty()) {
                service.publishAndNarrate(report, console, "clang-tidy")
            }
        },
    )
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
