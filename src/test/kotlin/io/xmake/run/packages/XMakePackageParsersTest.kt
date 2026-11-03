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
package io.xmake.run.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures are the outputs docs/roadmap-designs/xrepo-packages.md captured on xmake
 *  3.1.2, prologue lines and Windows paths included. */
class XMakePackageParsersTest {

    @Test
    fun `parses the declared packages and skips the lying detail lines`() {
        val output = """
            The package dependencies of project:
                require(tinytest): 1.0.0
                  -> version: 1.0.0
                  -> includedirs: C:\Users\dev\AppData\Local\.xmake\packages\t\tinytest\1.0.0
                require(tinyghost): 1.0.0
        """.trimIndent() + "\n"

        val entries = XMakePackageParsers.parseRequireList(output)

        assertEquals(
            listOf(
                XMakeRequireEntry("tinytest", "1.0.0"),
                XMakeRequireEntry("tinyghost", "1.0.0"),
            ),
            entries,
        )
    }

    @Test
    fun `keeps version ranges and external prefixes in the requirement`() {
        val output = """
            require(vcpkg::zlib): >=1.2.11
            require(conan::fmt): *
        """.trimIndent() + "\n"

        val entries = XMakePackageParsers.parseRequireList(output)

        assertEquals(listOf("vcpkg::zlib", "conan::fmt"), entries.map(XMakeRequireEntry::name))
        assertEquals(listOf(">=1.2.11", "*"), entries.map(XMakeRequireEntry::requirement))
    }

    @Test
    fun `a listing with no packages answers empty, not a failure`() {
        val output = "The package dependencies of project:\n"
        assertTrue(XMakePackageParsers.parseRequireList(output).isEmpty())
    }

    @Test
    fun `parses the info json with the references marker`() {
        val output = """
            [
                {
                    "require": "tinytest",
                    "version": "1.0.0",
                    "description": "A tiny test framework",
                    "repo": { "name": "localrepo", "url": "https://example.com/localrepo.git" },
                    "installdir": "C:\\packages\\t\\tinytest\\1.0.0",
                    "cachedir": "C:\\packages\\cache",
                    "platforms": ["windows", "linux"],
                    "references": [ { "dir": "C:\\proj", "date": "2024-01-01", "exists": true } ]
                }
            ]
        """.trimIndent() + "\n"

        val info = XMakePackageParsers.parsePackageInfo(output)!!

        assertEquals("tinytest", info.require)
        assertEquals("1.0.0", info.version)
        assertEquals("A tiny test framework", info.description)
        assertEquals("localrepo", info.repoName)
        assertEquals("https://example.com/localrepo.git", info.repoUrl)
        assertEquals("C:\\packages\\t\\tinytest\\1.0.0", info.installdir)
        assertEquals(listOf("windows", "linux"), info.platforms)
        assertTrue(info.installed)
        assertEquals("C:\\proj", info.references?.single()?.dir)
        assertEquals(true, info.references?.single()?.exists)
    }

    @Test
    fun `references absent means not installed while the repo stays`() {
        val output = """
            [
                { "require": "tinytest", "version": "1.0.0", "repo": { "name": "localrepo", "url": "https://x" } }
            ]
        """.trimIndent()

        val info = XMakePackageParsers.parsePackageInfo(output)!!

        assertFalse(info.installed)
        assertEquals("localrepo", info.repoName)
    }

    @Test
    fun `skips the cold-cache prologue lines before the info json`() {
        val output = """
            checking for platform windows ...
            checking for architecture x64 ...
            [{"require": "tinytest", "version": "1.0.0"}]
        """.trimIndent() + "\n"

        val info = XMakePackageParsers.parsePackageInfo(output)

        assertEquals("1.0.0", info?.version)
    }

    @Test
    fun `a package that exists nowhere parses as latest with no repo`() {
        val output = """[ { "require": "nosuchpkg", "version": "latest" } ]"""

        val info = XMakePackageParsers.parsePackageInfo(output)!!

        assertEquals("latest", info.version)
        assertNull(info.repoName)
        assertFalse(info.installed)
    }

    @Test
    fun `junk info output answers null instead of throwing`() {
        assertNull(XMakePackageParsers.parsePackageInfo("checking for platform ...\nno json here\n"))
    }

    @Test
    fun `parses search hits with description and repo`() {
        val output = """
            The package names:
                -> tinytest-1.0.0: A tiny test framework (in localrepo)
                -> tinytest-0.9.0: An older tiny test framework (in localrepo)
        """.trimIndent() + "\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertEquals(listOf("tinytest", "tinytest"), results.map { it.name })
        assertEquals(listOf("1.0.0", "0.9.0"), results.map { it.version })
        assertEquals("A tiny test framework", results[0].description)
        assertEquals("localrepo", results[0].repo)
    }

    @Test
    fun `parses dashed package names and tolerates an empty description`() {
        val output = "    -> mongo-c-driver-1.2.3-beta1:  (in xmake-repo)\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertEquals(1, results.size)
        assertEquals("mongo-c-driver", results[0].name)
        assertEquals("1.2.3-beta1", results[0].version)
        assertNull(results[0].description)
    }

    @Test
    fun `search junk answers no hits`() {
        assertTrue(XMakePackageParsers.parseSearchResults("nothing matched\n").isEmpty())
    }

    @Test
    fun `parses the depgraph json`() {
        val output = """
            {
                "packages": [
                    { "deps": ["zlib"], "name": "tinytest", "version": "1.0.0" },
                    { "deps": [], "name": "zlib", "version": "1.2.13" }
                ],
                "root_packages": [ "tinytest" ]
            }
        """.trimIndent()

        val graph = XMakePackageParsers.parseDepgraph(output)!!

        assertEquals(listOf("tinytest"), graph.rootPackages)
        assertEquals(2, graph.packages.size)
        assertEquals(listOf("zlib"), graph.packages.first { it.name == "tinytest" }.deps)
    }

    @Test
    fun `dependency children nest transitively and cut cycles`() {
        val graph = XMakePackageParsers.parseDepgraph(
            """
            {
                "packages": [
                    { "deps": ["b"], "name": "a", "version": "1" },
                    { "deps": ["c"], "name": "b", "version": "1" },
                    { "deps": ["b"], "name": "c", "version": "1" }
                ],
                "root_packages": [ "a" ]
            }
            """.trimIndent(),
        )!!

        val children = XMakePackageParsers.dependencyChildren(graph, "a")

        // a -> b -> c, and c's back-edge to b is cut.
        assertEquals(listOf("b", "c"), children.map { it.name })
    }

    @Test
    fun `no graph means no children and junk means no graph`() {
        assertTrue(XMakePackageParsers.dependencyChildren(null, "a").isEmpty())
        assertNull(XMakePackageParsers.parseDepgraph("error: something went wrong\n"))
    }
}
