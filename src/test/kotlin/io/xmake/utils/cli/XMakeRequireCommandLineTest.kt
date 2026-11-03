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

/** The `require` action lines the packages view drives; one action per line, like
 *  [XMakeServiceCommandLineTest]'s service grammar. */
class XMakeRequireCommandLineTest {

    private fun line(configure: XMakeCommandLine.() -> Unit): List<String> =
        xmake(CliInstallation("xmake", ToolkitHost())).apply(configure).arguments

    @Test
    fun `builds the read-only queries`() {
        assertEquals(listOf("require", "--list"), line { require { list = true } })
        assertEquals(
            listOf("require", "--info", "--format=json", "tinytest"),
            line { require { info = true; json = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--search", "tiny"),
            line { require { search = true; packages = listOf("tiny") } },
        )
        assertEquals(
            listOf("require", "--depgraph", "--format=json"),
            line { require { depgraph = true; json = true } },
        )
    }

    @Test
    fun `builds the mutations with -y baked in`() {
        assertEquals(
            listOf("require", "-y", "tinytest"),
            line { require { yes = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--uninstall", "-y", "tinytest"),
            line { require { yes = true; uninstall = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--upgrade", "-y"),
            line { require { yes = true; upgrade = true } },
        )
    }

    @Test
    fun `a second action on one command line is refused`() {
        val error = try {
            line {
                require { list = true }
                version()
            }
            null
        } catch (error: IllegalStateException) {
            error.message
        }
        assertEquals("XMake command line already starts with 'require'; add one action per command", error)
    }
}
