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
package io.xmake.utils.cli

import io.xmake.project.toolkit.ToolkitHost
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `test` action lines: the report flag rides the capability gate, the filters ride
 *  the front. */
class XMakeTestCommandLineTest {

    private fun lineOn(version: String, configure: XMakeCommandLine.() -> Unit): List<String> =
        xmake(CliInstallation("xmake", ToolkitHost(), version)).apply(configure).arguments

    @Test
    fun `the filters render and the report flag rides the gate`() {
        assertEquals(
            listOf("test", "group/*"),
            lineOn("3.1.2") { test { filters = listOf("group/*") } },
        )
        assertEquals(
            listOf("test", "group/*", "--output-on-failure"),
            lineOn("3.1.2") { test { filters = listOf("group/*"); outputOnFailure = true } },
        )
    }

    @Test
    fun `the report flag refuses construction below its version`() {
        val error = try {
            lineOn("2.8.7") { test { outputOnFailure = true } }
            null
        } catch (error: IllegalStateException) {
            error.message
        }
        assertEquals("--output-on-failure needs xmake 3.1.2", error)
    }
}
