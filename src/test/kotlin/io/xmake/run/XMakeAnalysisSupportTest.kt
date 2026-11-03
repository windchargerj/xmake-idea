/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License, Version 2.0 (the License);
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

import io.xmake.XMakePlatformTestCase

/**
 * The analysis runs are reached through the extension point: the CLion module provides the
 * Valgrind and perf implementations, and where it is absent, no executor answers. This drives
 * the extension lookup in the CLion the plugin runs on.
 */
class XMakeAnalysisSupportTest : XMakePlatformTestCase() {

    fun testValgrindExecutorAnswers() {
        val support = XMakeAnalysisSupport.forExecutor("Valgrind")
        assertNotNull("the CLion module provides the Valgrind analysis", support)
        assertTrue("Valgrind" in support!!.executorIds)
    }

    fun testProfilerExecutorAnswers() {
        val support = XMakeAnalysisSupport.forExecutor("Profiler")
        assertNotNull("the CLion module provides the perf analysis", support)
        assertTrue("Profiler" in support!!.executorIds)
    }

    fun testUnknownExecutorFindsNothing() {
        assertNull(XMakeAnalysisSupport.forExecutor("NoSuchExecutor"))
    }
}
