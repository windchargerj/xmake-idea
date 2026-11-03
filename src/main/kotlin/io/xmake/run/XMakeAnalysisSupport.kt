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
package io.xmake.run

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import io.xmake.project.console.XMakeConsole

/**
 * The door to the analysis runs - Valgrind, perf, anything whose entry exists only in CLion.
 * The optional CLion module implements this behind an extension point; where that module is
 * absent, no analysis executor answers and the entries simply do not appear.
 */
interface XMakeAnalysisSupport {

    /** The executor ids this support handles, matched against the CLion executors by id. */
    val executorIds: Set<String>

    /** Builds the run state for one of this support's executors, or returns null for others. */
    fun createState(
        executorId: String,
        configuration: XMakeRunConfiguration,
        environment: ExecutionEnvironment,
    ): XMakeAnalysisState?

    companion object {
        @JvmField
        val EP = ExtensionPointName<XMakeAnalysisSupport>("io.xmake.analysisSupport")

        /** The first support that handles the executor, or null. */
        fun forExecutor(executorId: String): XMakeAnalysisSupport? =
            EP.extensionList.firstOrNull { executorId in it.executorIds }
    }
}

/**
 * The state an analysis run executes through. The concrete state - its commands, its data
 * files, its result presentation - lives entirely in the CLion module; the runner only knows
 * it can be executed.
 */
interface XMakeAnalysisState : RunProfileState {

    /** Runs the analysis: configure, build, launch the tool, present the results. The state
     *  resolves its own execution service from the project. */
    suspend fun execute(
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor
}
