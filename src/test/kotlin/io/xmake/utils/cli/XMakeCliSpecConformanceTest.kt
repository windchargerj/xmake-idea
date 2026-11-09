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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specs bound the DSL: every long flag the DSL can emit must appear in the help of
 * the xmake that will receive it, and every capability entry's boundary must match the
 * flags the two captured helps actually carry. The fixtures are the verbatim `--help`
 * captures of the two toolkits this machine holds - 2.8.7 on WSL, 3.1.2 on Windows -
 * and this net is where their drift from our grammar gets caught: the first capture
 * round alone falsified a matrix entry (status and logs answer on 2.8.7) and surfaced
 * a missing one (depgraph does not).
 */
class XMakeCliSpecConformanceTest {

    private fun vocabulary(action: String, version: String): Set<String> {
        val help = javaClass.getResourceAsStream("/clispecs/$action-help-$version.txt")!!
            .readBytes().decodeToString()
        return Regex("--[a-zA-Z][a-zA-Z0-9-]*").findAll(help).map { it.value }.toSet()
    }

    private fun render(version: String, configure: XMakeCommandLine.() -> Unit): List<String> =
        xmake(CliInstallation("xmake", ToolkitHost(), version)).apply(configure).arguments
            .filter { it.startsWith("--") }
            .map { it.substringBefore('=') }

    private fun conforms(flags: List<String>, vocabulary: Set<String>, where: String) {
        val stray = flags.filter { it !in vocabulary }
        assertTrue("$where emits flags no help documents: $stray", stray.isEmpty())
    }

    @Test
    fun `every require flag the DSL emits is a flag some help documents`() {
        val maxRequire: XMakeCommandLine.() -> Unit = {
            require {
                list = true; info = true; search = true; uninstall = true; upgrade = true
                depgraph = true; clean = true; fetch = true; download = true; check = true
                export = true; import = true; scan = true; shallow = true
                buildFromSource = true; force = true; json = true
                packageDir = "dir"; extra = "{}"; packages = listOf("tinytest")
            }
        }

        conforms(
            render("3.1.2", maxRequire),
            vocabulary("require", "3.1.2"),
            "require on 3.1.2",
        )
        conforms(
            render("2.8.7") {
                require {
                    list = true; info = true; search = true; uninstall = true; upgrade = true
                    clean = true; fetch = true; export = true; import = true; scan = true
                    shallow = true; buildFromSource = true; force = true
                    packageDir = "dir"; extra = "{}"; packages = listOf("tinytest")
                }
            },
            vocabulary("require", "2.8.7"),
            "require on 2.8.7",
        )
    }

    @Test
    fun `every service verb and modifier the DSL emits is a flag some help documents`() {
        for (version in listOf("2.8.7", "3.1.2")) {
            for (verb in XMakeServiceVerb.entries) {
                val flags = render(version) {
                    service {
                        this.verb = verb
                        kind = XMakeServiceKind.REMOTE_BUILD
                        session = "s"
                        all = true
                        pullPath = "build"
                        if (verb.takesValue) {
                            user = "root"
                            xmakeSrc = "/xmake"
                        }
                    }
                }
                conforms(flags, vocabulary("service", version), "service $verb on $version")
            }
        }
    }

    @Test
    fun `every test flag the DSL emits is a flag some help documents`() {
        conforms(
            render("3.1.2") { test { filters = listOf("g/*"); outputOnFailure = true } },
            vocabulary("test", "3.1.2"),
            "test on 3.1.2",
        )
    }

    /** The version contracts the DSL declares, held against the captured helps and the
     *  oracle: a gated flag refuses construction below its version, the older help does
     *  not carry it, and the newer one does - declaration, spec, and refusal agree. */
    @Test
    fun `the declared version contracts match the flags the helps actually carry`() {
        data class Gated(val action: String, val flag: String, val ask: XMakeCommandLine.() -> Unit)

        val gated = listOf(
            Gated("require", "--download") { require { download = true } },
            Gated("require", "--check") { require { check = true } },
            Gated("require", "--depgraph") { require { depgraph = true } },
            Gated("require", "--format") { require { json = true } },
            Gated("test", "--output-on-failure") { test { outputOnFailure = true } },
        )
        for ((action, flag, ask) in gated) {
            val refused = try {
                xmake(CliInstallation("xmake", ToolkitHost(), "2.8.7")).apply(ask)
                false
            } catch (_: IllegalStateException) {
                true
            }
            assertTrue("$flag renders on 2.8.7 despite the contract", refused)
            assertTrue("$flag present in 2.8.7's $action help despite the contract", flag !in vocabulary(action, "2.8.7"))
            assertTrue("$flag missing from 3.1.2's $action help", flag in vocabulary(action, "3.1.2"))
        }
    }
}
