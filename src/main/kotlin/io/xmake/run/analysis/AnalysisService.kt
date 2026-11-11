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
package io.xmake.run.analysis

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import io.xmake.project.console.XMAKE_ANALYSIS_TAB_TITLE
import io.xmake.project.console.XMAKE_TOOL_WINDOW_ID
import io.xmake.project.console.XMakeConsole
import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The project's analysis findings: the report of the last analysis run - a sanitized run,
 * clang-tidy - published on the message bus so the analysis view, the editor marks, and
 * anything else stay in step.
 *
 * One report replaces the previous one - the view always describes the last run, the way
 * coverage data does - and a plain run clears the current report again. The last few
 * reports also stay in a history the view's picker and the Accumulate switch read; the
 * history survives a plain run's clear (an ordinary run must not wipe a collected session)
 * and only the view's Clear drops it. Showing an older one is presentation-only and never
 * republishes it.
 */
@Service(Service.Level.PROJECT)
internal class AnalysisService(private val project: Project) {

    /** The findings of the last sanitized run, or null after a clear. */
    var report: AnalysisReport? = null
        private set

    /** The run configuration of the last sanitized run, for the view's rerun button. */
    var lastSanitizedSettings: RunnerAndConfigurationSettings? = null
        private set

    /** The last few published reports, newest first, for the view's history picker. */
    val history: List<AnalysisReport>
        get() = reportHistory.toList()

    private val reportHistory = ArrayDeque<AnalysisReport>()

    /**
     * The claim a check takes before its task is even submitted: the executor's single lane
     * may sit behind a long build, so without a claim every click of Run would queue
     * another full `xmake check` behind the first. Released when the registered process
     * takes over ([registerCheckProcess]), when the run bails early, or on stop.
     */
    private val checkClaimed = AtomicBoolean(false)

    /** The queued check's job, held so the stop button can cancel it before it starts. */
    private var checkJob: Deferred<*>? = null

    /** The process of the check running now, held so the view's stop button can kill it. */
    @Volatile
    private var checkProcess: ProcessHandler? = null

    /** Whether a check registered through [registerCheckProcess] still runs. */
    val isCheckRunning: Boolean
        get() = checkProcess?.isProcessTerminated == false

    /** Whether a check is claimed but its process is not registered yet: the task may sit
     *  queued on the executor's lane, or still be building the compile database. */
    val isCheckQueued: Boolean
        get() = checkClaimed.get()

    /** Whether a check occupies the pipeline in any way - what the Run button disables on. */
    val isCheckActive: Boolean
        get() = isCheckQueued || isCheckRunning

    /**
     * Claims the one check slot; false when a check is already queued or running. Called
     * from the Run entry before anything is submitted, so repeated clicks cannot pile up.
     */
    fun beginCheck(): Boolean = checkClaimed.compareAndSet(false, true)

    /** Releases the claim when no process took it over - an early bail or a failed launch. */
    fun endCheck() {
        checkClaimed.set(false)
    }

    /** Publishes the findings of one run; null clears the current report - the view's
     *  content, not its history, which a plain run has no business wiping. */
    fun publish(report: AnalysisReport?) {
        this.report = report
        if (report != null) {
            reportHistory.addFirst(report)
            while (reportHistory.size > HISTORY_LIMIT) reportHistory.removeLast()
        }
        project.messageBus.syncPublisher(TOPIC).reportPublished(report)
    }

    /** Drops the current report and the history - the Clear button's clean slate, and the
     *  only path that wipes the collected session. */
    fun clearAll() {
        report = null
        reportHistory.clear()
        project.messageBus.syncPublisher(TOPIC).reportPublished(null)
    }

    /**
     * Remembers [handler] as the check the view can stop. The registration clears itself
     * when the process ends - completion, failure, or the stop button, whichever comes first -
     * and takes over from the queued claim: from here on the process is the running state.
     */
    fun registerCheckProcess(handler: ProcessHandler) {
        checkClaimed.set(false)
        checkProcess = handler
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                if (checkProcess === handler) checkProcess = null
            }
        })
    }

    /** Remembers the submitted job so [stopCheck] can cancel a check still queued on the lane. */
    fun registerCheckJob(job: Deferred<*>) {
        checkJob = job
    }

    /**
     * Stops the check: a queued one is cancelled before it ever starts (its claim released,
     * since its task may never run to do that itself); a running one has its process
     * destroyed - whatever it found until then still publishes.
     */
    fun stopCheck() {
        if (checkClaimed.get()) {
            checkJob?.cancel()
            endCheck()
            return
        }
        checkProcess?.destroyProcess()
    }

    /**
     * Publishes [report] and narrates it the way both producing runs did: the console line
     * that says what was found and points at the view, and the view itself - brought to
     * the front when the run found something, the way the valgrind panel is. The run was
     * started to see its findings; leaving them behind another tool window makes the user
     * hunt for the result they asked for. A clean run stays where it is: the console
     * already says so, and an empty view would only interrupt.
     * Runs on the EDT hop; [console] is optional because a sanitized run may have none.
     */
    fun publishAndNarrate(report: AnalysisReport, console: XMakeConsole?, sourceLabel: String) {
        invokeLater {
            if (project.isDisposed) return@invokeLater
            publish(report)
            console?.print(
                if (report.issues.isEmpty()) {
                    "$sourceLabel: no findings\n"
                } else {
                    "$sourceLabel: ${report.totalFindings} finding(s) at " +
                            "${report.issues.size} unique place(s) - see the Analysis tab\n"
                },
                ConsoleViewContentType.SYSTEM_OUTPUT,
            )
            if (report.issues.isNotEmpty()) {
                showAnalysisView(project)
            }
        }
    }

    fun rememberSanitizedSettings(settings: RunnerAndConfigurationSettings) {
        lastSanitizedSettings = settings
    }

    /** Re-runs the configuration the last sanitized run used. */
    fun rerunLast() {
        val settings = lastSanitizedSettings ?: return
        val executor: Executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
            ?: return
        ExecutionUtil.runConfiguration(settings, executor)
    }

    companion object {
        /** The findings of the last run, or null when they were cleared. */
        val TOPIC: Topic<Listener> = Topic("XMake analysis findings", Listener::class.java)

        /** How many reports the history keeps; the picker shows time, source, and count. */
        private const val HISTORY_LIMIT = 20

        fun getInstance(project: Project): AnalysisService = project.service()
    }

    fun interface Listener {

        fun reportPublished(report: AnalysisReport?)
    }
}

/** Brings the findings to the front: the XMake window with its Analysis tab selected.
 *  The view lives inside the build window, so there is no analysis window to look up. */
internal fun showAnalysisView(project: Project) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(XMAKE_TOOL_WINDOW_ID) ?: return
    toolWindow.activate(null)
    val content = toolWindow.contentManager.contents
        .firstOrNull { it.displayName == XMAKE_ANALYSIS_TAB_TITLE }
    if (content != null) {
        toolWindow.contentManager.setSelectedContent(content)
    }
}
