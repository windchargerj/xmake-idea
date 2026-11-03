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
import io.xmake.run.analysis.ANALYSIS_TOOL_WINDOW_ID
import io.xmake.run.analysis.AnalysisService
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
        val command = project.xmakeCommands(profile).createClangTidyCheck()
        val output = try {
            executor.captureStandardOutput(command)
        } catch (error: Exception) {
            Log.warn("clang-tidy check failed", error)
            invokeLater {
                if (!project.isDisposed) {
                    console.print(
                        "clang-tidy: check failed: ${error.message}\n",
                        ConsoleViewContentType.ERROR_OUTPUT,
                    )
                    project.notify("clang-tidy failed: ${error.message}")
                }
            }
            return@submit
        }
        val report = ClangTidyReportParser().apply { feed(output) }.finish(project.name ?: "project")
        invokeLater {
            if (project.isDisposed) return@invokeLater
            AnalysisService.getInstance(project).publish(report)
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

private val Log = Logger.getInstance("io.xmake.run.tidy.ClangTidyRun")
