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
package io.xmake.debug.clion

import com.intellij.execution.runners.ExecutionEnvironment
import io.xmake.debug.clion.perf.XMakePerfState
import io.xmake.debug.clion.valgrind.XMakeValgrindState
import io.xmake.run.XMakeAnalysisState
import io.xmake.run.XMakeAnalysisSupport
import io.xmake.run.XMakeRunConfiguration

/**
 * The CLion analysis runs: Valgrind under CLion's own executor, perf under CLion's profiler
 * executor. Both entries exist only in CLion, so the whole implementation - the states, the
 * tool execution, the result presentation - lives here in the optional CLion module; where
 * this module is absent, the runner finds no analysis support and the entries do not appear.
 */
class ClionAnalysisSupport : XMakeAnalysisSupport {

    override val executorIds: Set<String> = setOf(VALGRIND_EXECUTOR_ID, PROFILER_EXECUTOR_ID)

    override fun createState(
        executorId: String,
        configuration: XMakeRunConfiguration,
        environment: ExecutionEnvironment,
    ): XMakeAnalysisState? = when (executorId) {
        VALGRIND_EXECUTOR_ID -> XMakeValgrindState.create(configuration, environment)
        PROFILER_EXECUTOR_ID -> XMakePerfState.create(configuration, environment)
        else -> null
    }

    companion object {
        /** CLion's Valgrind executor; matched by id so no internal class is referenced. */
        private const val VALGRIND_EXECUTOR_ID = "Valgrind"

        /** CLion's profiler executor (perf on Linux). */
        private const val PROFILER_EXECUTOR_ID = "Profiler"
    }
}
