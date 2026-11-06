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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `require` action lines the packages view drives; one action per line, like
 *  [XMakeServiceCommandLineTest]'s service grammar. */
class XMakeRequireCommandLineTest {

    private fun line(configure: XMakeCommandLine.() -> Unit): List<String> =
        xmake(CliInstallation("xmake", ToolkitHost())).apply(configure).arguments

    @Test
    fun `the housekeeping verbs render their flags and packages`() {
        val rendered = line {
            require {
                clean = true
                fetch = true
                download = true
                check = true
                export = true
                import = true
                packages = listOf("zlib", "/tmp/export.tar")
            }
        }

        for (flag in listOf("--clean", "--fetch", "--download", "--check", "--export", "--import")) {
            assertTrue("missing " + flag, rendered.contains(flag))
        }
        assertTrue(rendered.contains("zlib"))
        assertTrue(rendered.contains("/tmp/export.tar"))
    }

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
    fun `the upgrade, clean, and scan scopes ride their names`() {
        assertEquals(
            listOf("require", "--upgrade", "-y", "tinytest"),
            line { require { yes = true; upgrade = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--clean", "tinytest"),
            line { require { clean = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--clean"),
            line { require { clean = true } },
        )
        assertEquals(
            listOf("require", "--scan", "tinytest"),
            line { require { scan = true; packages = listOf("tinytest") } },
        )
        assertEquals(
            listOf("require", "--scan"),
            line { require { scan = true } },
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
