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

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * The project's sanitizer findings: the report of the last sanitized run, published on the
 * message bus so the analysis view, the editor marks, and anything else stay in step.
 *
 * One report replaces the previous one - the view always describes the last run, the way
 * coverage data does - and a plain run without a sanitizer clears the findings again.
 */
@Service(Service.Level.PROJECT)
internal class SanitizerAnalysisService(private val project: Project) {

    /** The findings of the last sanitized run, or null after a clear. */
    var report: SanitizerReport? = null
        private set

    /** The run configuration of the last sanitized run, for the view's rerun button. */
    var lastSanitizedSettings: RunnerAndConfigurationSettings? = null
        private set

    /** Publishes the findings of one run; null clears the view for a plain run. */
    fun publish(report: SanitizerReport?) {
        this.report = report
        project.messageBus.syncPublisher(TOPIC).reportPublished(report)
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
        val TOPIC: Topic<Listener> = Topic("XMake sanitizer findings", Listener::class.java)

        fun getInstance(project: Project): SanitizerAnalysisService = project.service()
    }

    fun interface Listener {

        fun reportPublished(report: SanitizerReport?)
    }
}

/** The tool window id of the sanitizer analysis view. */
internal const val SANITIZER_TOOL_WINDOW_ID = "XMake Analysis"
