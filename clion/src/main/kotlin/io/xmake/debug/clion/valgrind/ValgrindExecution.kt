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
package io.xmake.debug.clion.valgrind

import com.intellij.execution.ExecutionException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.xmake.project.console.XMakeConsole
import io.xmake.run.ensureProjectIsOpen
import io.xmake.run.stopAfterFailedStart
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * Prepares a Valgrind run: configures the profile in xmake's valgrind mode, builds, then
 * starts the target under Valgrind through `xmake l runtool.lua`. The report is announced on
 * exit; the IDE's memory view picks it up through its import action.
 */
internal suspend fun executeValgrind(
    executor: XMakeExecutor,
    state: XMakeValgrindState,
    environment: ExecutionEnvironment,
    console: XMakeConsole,
): RunContentDescriptor {
    executor.execute(
        console, state.configureCommand,
        XMakeConsoleOptions(showConsole = false, showOnFailure = true),
    )
    executor.execute(
        console,
        state.buildCommand,
        XMakeConsoleOptions(showConsole = false, showOnFailure = true, showProblems = true),
    )

    val processHandler = state.startProcess()
    return try {
        var liveSession: XMakeValgrindNativeSession? = null
        val content = withContext(Dispatchers.EDT) {
            ensureProjectIsOpen(environment.project)
            // The native composition: the panel and its model exist from the start of the
            // run, and the console is wrapped so the platform hosts both in one run content
            // with the results as their own tab, like the native runner does. Without the
            // native module the panel is shown separately once the run has finished.
            val session = openXMakeValgrindNativeSession(environment.project)
            val runConsole = state.consoleView(environment.executor)
            val wrapped = if (session != null && runConsole != null) {
                session.wrapConsole(environment.project, runConsole)
            } else {
                null
            }
            liveSession = if (wrapped != null) session else null
            Log.info(
                "Valgrind run presentation: session=${session != null}, console=${runConsole != null}, " +
                    "nativeWrapper=${wrapped != null}",
            )
            if (session == null || wrapped == null) {
                // The absence is part of the result: say where the findings went instead of
                // leaving a console that looks like an ordinary run.
                console.print(
                    "The native Valgrind panel is not available in this CLion; the findings are " +
                        "printed in this console and kept in the report file.",
                    com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT,
                )
            }
            showRunContent(
                state.createExecutionResult(environment.executor, processHandler, wrapped ?: runConsole),
                environment,
            ) ?: throw ExecutionException("Failed to create the XMake run descriptor")
        }
        val session = liveSession
        val watcher = session?.let {
            ValgrindReportWatcher(content, state.reportFile) { errors ->
                it.addNewErrors(errors, state::toLocalPath)
            }
        }
        processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                watcher?.dispose()
                val errors = parseValgrindReport(state.reportFile)
                presentValgrindErrors(
                    environment.project,
                    state.runConsole,
                    errors,
                    state::toLocalSource,
                )
                session?.addNewErrors(errors, state::toLocalPath)
                if (errors.isNotEmpty()) {
                    revealValgrindResults(environment.project, content, environment.executor.toolWindowId)
                }
                notifyValgrindReport(environment.project, state.reportFile, errors)
            }
        })
        content
    } catch (error: Throwable) {
        processHandler.stopAfterFailedStart(error)
        throw error
    }
}

/**
 * Brings the results panel to the front once a finished run found something. The panel is
 * the reason the run happened: leaving the findings behind a console tab makes the user hunt
 * for them, and the native wrapper only bounces the tab. A run without findings stays where
 * it is - the console already says so, and pulling the window forward for an empty tree
 * helps nobody.
 */
private fun revealValgrindResults(project: Project, content: RunContentDescriptor, toolWindowId: String) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val ui = content.runnerLayoutUi ?: return@invokeLater
        selectValgrindResults(ui)
        ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)?.activate(null)
    }
}

/** Selects the panel's tab in [ui]; a run content without the panel keeps its console. */
internal fun selectValgrindResults(ui: com.intellij.execution.ui.RunnerLayoutUi) {
    val panel = ui.contents.firstOrNull { content -> content.displayName == VALGRIND_PANEL } ?: return
    ui.contentManager.setSelectedContent(panel)
}

/** The results tab's name, the one the native wrapper gives the panel content. */
private const val VALGRIND_PANEL = "Valgrind"

/**
 * Polls the report while a run is in progress and hands every snapshot of it to [onReport],
 * so a live panel follows the tool that writes the report, the way the native runner shows
 * errors as valgrind finds them.
 */
private class ValgrindReportWatcher(
    parentDisposable: Disposable,
    private val reportFile: File,
    private val onReport: (List<XMakeValgrindError>) -> Unit,
) : Disposable {

    private val poll = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(
            { onReport(parseValgrindReport(reportFile)) },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

    init {
        // The watcher rides on the run content: disposed when the run tab closes,
        // not when the whole project does.
        Disposer.register(parentDisposable, this)
    }

    override fun dispose() {
        poll.cancel(false)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 300L
    }
}

/** Summarizes what the run found; the full report stays on disk for deeper inspection. */
private fun notifyValgrindReport(
    project: Project,
    reportFile: File,
    errors: List<XMakeValgrindError>,
) {
    val detail = when {
        errors.isEmpty() && reportFile.isFile ->
            "Valgrind found no problems. Full report: ${reportFile.absolutePath}"

        errors.isEmpty() ->
            "Valgrind produced no report. Check the run output in the console for errors from valgrind or xmake."

        else -> {
            val first = errors.first()
            val frame = first.innermostSourceFrame
            val location = frame?.sourceFile?.let { "${it.path}:${frame.line ?: 1}" } ?: "unknown location"
            "Valgrind found ${errors.size} problem(s); see the run console. " +
                "First: [${first.kind}] ${first.caption} at $location. " +
                "Full report: ${reportFile.absolutePath}"
        }
    }
    NotificationGroupManager.getInstance()
        .getNotificationGroup("XMake.NotificationGroup")
        .createNotification("XMake Valgrind run finished", detail, NotificationType.INFORMATION)
        .notify(project)
}

private val Log = Logger.getInstance("io.xmake.debug.clion.valgrind.ValgrindExecution")
