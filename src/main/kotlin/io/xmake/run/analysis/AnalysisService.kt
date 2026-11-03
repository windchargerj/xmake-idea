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
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * The project's analysis findings: the report of the last analysis run - a sanitized run,
 * clang-tidy - published on the message bus so the analysis view, the editor marks, and
 * anything else stay in step.
 *
 * One report replaces the previous one - the view always describes the last run, the way
 * coverage data does - and a plain run clears the findings again. The last few reports also
 * stay in a history the view's picker reads; showing an older one is presentation-only and
 * never republishes it.
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

    /** The process of the check running now, held so the view's stop button can kill it. */
    @Volatile
    private var checkProcess: ProcessHandler? = null

    /** Whether a check registered through [registerCheckProcess] still runs. */
    val isCheckRunning: Boolean
        get() = checkProcess?.isProcessTerminated == false

    /** Publishes the findings of one run; null clears the view - and its history - again. */
    fun publish(report: AnalysisReport?) {
        this.report = report
        if (report == null) {
            reportHistory.clear()
        } else {
            reportHistory.addFirst(report)
            while (reportHistory.size > HISTORY_LIMIT) reportHistory.removeLast()
        }
        project.messageBus.syncPublisher(TOPIC).reportPublished(report)
    }

    /**
     * Remembers [handler] as the check the view can stop. The registration clears itself
     * when the process ends - completion, failure, or the stop button, whichever comes first.
     */
    fun registerCheckProcess(handler: ProcessHandler) {
        checkProcess = handler
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                if (checkProcess === handler) checkProcess = null
            }
        })
    }

    /** Stops the running check, if one runs; whatever it found until then still publishes. */
    fun stopCheck() {
        checkProcess?.destroyProcess()
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

/** The tool window id of the sanitizer analysis view. */
internal const val ANALYSIS_TOOL_WINDOW_ID = "XMake Analysis"
