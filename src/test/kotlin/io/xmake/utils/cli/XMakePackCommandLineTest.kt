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

/** The `pack` action lines the pack run configuration drives. The option-before-names order
 *  is the whole point of the [PackScope]: xmake's plugin-task parser swallows everything
 *  after the first package name as another name (docs/roadmap-designs/packaging.md, F8). */
class XMakePackCommandLineTest {

    private fun line(configure: XMakeCommandLine.() -> Unit): List<String> =
        xmake(CliInstallation("xmake", ToolkitHost())).apply(configure).arguments

    @Test
    fun `builds options ahead of the package names`() {
        assertEquals(
            listOf(
                "pack",
                "-f", "zip,nsis",
                "-o", "build/dist",
                "--basename=demo-1.0",
                "hello",
            ),
            line {
                pack {
                    formats = "zip,nsis"
                    outputDirectory = "build/dist"
                    basename = "demo-1.0"
                    names = listOf("hello")
                }
            },
        )
    }

    @Test
    fun `empty options are omitted and names always come last`() {
        assertEquals(listOf("pack", "hello", "world"), line { pack { names = listOf("hello", "world") } })
    }

    @Test
    fun `packs every declared xpack when no name is set`() {
        assertEquals(listOf("pack"), line { pack { } })
    }

    @Test
    fun `autobuild=n is spelled out and the y default is left to xmake`() {
        assertEquals(listOf("pack", "--autobuild=n"), line { pack { autoBuild = false } })
        assertEquals(listOf("pack"), line { pack { autoBuild = true } })
    }

    @Test
    fun `extra arguments ride ahead of the names`() {
        assertEquals(
            listOf("pack", "-j", "4", "verbose-subcommand", "hello"),
            line {
                pack {
                    extraArguments = listOf("-j", "4", "verbose-subcommand")
                    names = listOf("hello")
                }
            },
        )
    }

    @Test
    fun `a second action on one command line is refused`() {
        val error = try {
            line {
                pack { }
                version()
            }
            null
        } catch (error: IllegalStateException) {
            error.message
        }
        assertEquals("XMake command line already starts with 'pack'; add one action per command", error)
    }
}

