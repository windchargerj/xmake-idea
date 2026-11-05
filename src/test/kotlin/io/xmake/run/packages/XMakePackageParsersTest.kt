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
import io.xmake.run.packages.ui.upstreamLinks
import org.junit.Test

/** The fixtures are the outputs docs/roadmap-designs/xrepo-packages.md and
 *  docs/roadmap-designs/audit-packages.md captured on xmake - 3.1.2 on Windows, 2.8.7 on
 *  WSL - prologue lines, ANSI coloring, and CRLF endings included. */
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

        val info = XMakePackageParsers.parsePackageInfo(output, "tinytest")!!

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

        val info = XMakePackageParsers.parsePackageInfo(output, "tinytest")!!

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

        val info = XMakePackageParsers.parsePackageInfo(output, "tinytest")

        assertEquals("1.0.0", info?.version)
    }

    @Test
    fun `a package that exists nowhere parses as latest with no repo`() {
        val output = """[ { "require": "nosuchpkg", "version": "latest" } ]"""

        val info = XMakePackageParsers.parsePackageInfo(output, "nosuchpkg")!!

        assertEquals("latest", info.version)
        assertNull(info.repoName)
        assertFalse(info.installed)
    }

    @Test
    fun `the dependency closure answers the asked package, not the first element`() {
        val output = """
            checking for platform ...
            [
                { "require": "openssl", "version": "1.1.1-w", "description": "A toolkit for TLS" },
                { "require": "zstd", "version": "v1.5.7", "description": "Fast real-time compression" },
                { "require": "rsync", "version": "3.4.1", "description": "Fast incremental file transfer" }
            ]
        """.trimIndent()

        val info = XMakePackageParsers.parsePackageInfo(output, "rsync")!!

        assertEquals("rsync", info.require)
        assertEquals("3.4.1", info.version)
        assertEquals("Fast incremental file transfer", info.description)
    }

    @Test
    fun `a name the closure does not carry answers null instead of another package`() {
        val output = """[ { "require": "openssl", "version": "1.1.1-w" } ]"""

        assertNull(XMakePackageParsers.parsePackageInfo(output, "rsync"))
    }

    @Test
    fun `the asked name matches through a version ask and an external prefix`() {
        val output = """[ { "require": "vcpkg::zlib >=1.3", "version": "1.3.1" } ]"""

        val info = XMakePackageParsers.parsePackageInfo(output, "ZLIB")!!

        assertEquals("1.3.1", info.version)
    }

    @Test
    fun `the plain-text info answer parses the asked block`() {
        val output = """
            The package info of project:
                require(openssl):
                  -> description: A robust, commercial-grade, and full-featured toolkit for TLS and SSL.
                  -> version: 1.1.1-w
                  -> license: Apache-2.0
                  -> repo: xmake-repo https://github.com/xmake-io/xmake-repo.git master
                  -> cachedir: C:\packages\cache\openssl\1.1.1-w
                  -> installdir: C:\packages\pkg\o\openssl\1.1.1-w\34138
                  -> platforms: cross, harmony, bsd, linux, macosx, windows, mingw, iphoneos, android

                require(ninja):
                  -> description: Small build system for use with gyp or CMake.
                  -> version: v1.13.2
        """.trimIndent() + "\n"

        val info = XMakePackageParsers.parsePackageInfoText(output, "openssl")!!

        assertEquals("openssl", info.require)
        assertEquals("1.1.1-w", info.version)
        assertEquals("A robust, commercial-grade, and full-featured toolkit for TLS and SSL.", info.description)
        assertEquals("xmake-repo", info.repoName)
        assertEquals("https://github.com/xmake-io/xmake-repo.git", info.repoUrl)
        assertEquals("C:\\packages\\pkg\\o\\openssl\\1.1.1-w\\34138", info.installdir)
        assertTrue(info.platforms.containsAll(listOf("linux", "windows", "mingw")))
        assertFalse(info.installed)
    }

    @Test
    fun `the plain-text answer skips blocks that are not the asked package`() {
        val output = """
            require(ninja):
              -> version: v1.13.2
            require(rsync):
              -> version: 3.4.1
        """.trimIndent()

        val info = XMakePackageParsers.parsePackageInfoText(output, "rsync")!!

        assertEquals("3.4.1", info.version)
        assertNull(XMakePackageParsers.parsePackageInfoText(output, "openssl"))
    }

    @Test
    fun `junk plain-text info answers null instead of throwing`() {
        assertNull(XMakePackageParsers.parsePackageInfoText("checking for platform ...\nnothing here\n", "rsync"))
    }

    @Test
    fun `a forge source answers the project and its readme`() {
        val (home, docs) = upstreamLinks("https://github.com/madler/zlib/archive/refs/tags/v1.3.1.tar.gz")

        assertEquals("https://github.com/madler/zlib", home)
        assertEquals("https://github.com/madler/zlib#readme", docs)
    }

    @Test
    fun `a mirror host drops its prefix to the project domain`() {
        val (home, docs) = upstreamLinks("https://download.samba.org/pub/rsync/src/rsync-3.4.1.tar.gz")

        assertEquals("https://samba.org", home)
        assertNull(docs)
    }

    @Test
    fun `no source answers no links`() {
        val (home, docs) = upstreamLinks(null)

        assertNull(home)
        assertNull(docs)
    }

    @Test
    fun `junk info output answers null instead of throwing`() {
        assertNull(XMakePackageParsers.parsePackageInfo("checking for platform ...\nno json here\n", "tinytest"))
    }

    @Test
    fun `the whole closure parses in the order xmake reported it`() {
        val output = """
            checking for platform ...
            [
                { "require": "openssl", "version": "1.1.1-w", "description": "A toolkit for TLS" },
                { "require": "zstd", "version": "v1.5.7" },
                { "require": "rsync", "version": "3.4.1", "description": "Fast incremental file transfer" }
            ]
        """.trimIndent()

        val closure = XMakePackageParsers.parsePackageClosure(output)

        assertEquals(listOf("openssl", "zstd", "rsync"), closure.map { it.require })
        assertEquals("A toolkit for TLS", closure[0].description)
        assertNull(closure[1].description)
        assertEquals("Fast incremental file transfer", closure[2].description)
    }

    @Test
    fun `a single-object info answer is a closure of one`() {
        val closure = XMakePackageParsers.parsePackageClosure("""{ "require": "tinytest", "version": "1.0.0" }""")

        assertEquals(1, closure.size)
        assertEquals("tinytest", closure.single().require)
    }

    @Test
    fun `junk closure output answers empty instead of throwing`() {
        assertTrue(XMakePackageParsers.parsePackageClosure("checking for platform ...\nno json here\n").isEmpty())
    }

    @Test
    fun `the plain-text answer parses the whole closure in reported order`() {
        val output = """
            The package info of project:
                require(openssl):
                  -> description: A toolkit for TLS.
                  -> version: 1.1.1-w
                  -> repo: xmake-repo https://github.com/xmake-io/xmake-repo.git master
                require(ninja):
                  -> version: v1.13.2
                require(rsync):
                  -> description: Fast incremental file transfer.
                  -> version: 3.4.1
        """.trimIndent()

        val closure = XMakePackageParsers.parsePackageClosureText(output)

        assertEquals(listOf("openssl", "ninja", "rsync"), closure.map { it.require })
        assertEquals("1.1.1-w", closure[0].version)
        assertEquals("xmake-repo", closure[0].repoName)
        assertEquals("https://github.com/xmake-io/xmake-repo.git", closure[0].repoUrl)
        assertEquals(listOf("linux", "windows"), XMakePackageParsers.parsePackageClosureText(
            "require(zlib):\n  -> platforms: linux, windows\n"
        ).single().platforms)
    }

    @Test
    fun `parses search hits with description and repo`() {
        val output = """
            The package names:
                -> tinytest-1.0.0: A tiny test framework (in localrepo)
                -> tinytest-0.9.0: An older tiny test framework (in localrepo)
        """.trimIndent() + "\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertEquals(listOf("tinytest", "tinytest"), results.hits.map { it.name })
        assertEquals(listOf("1.0.0", "0.9.0"), results.hits.map { it.version })
        assertEquals("A tiny test framework", results.hits[0].description)
        assertEquals("localrepo", results.hits[0].repo)
    }

    /** The byte-level capture of docs/roadmap-designs/audit-packages.md, section 1.1:
     *  full SGR coloring on piped output, CRLF endings, the `The package names:` header,
     *  and hit tokens that keep the repository's raw `add_versions` strings. The zlib and
     *  zlib-ng hit lines are verbatim; the zopfli line carries the `zopfli-2021.06.14`
     *  token that capture's section 2 table parsed, with a stand-in description. */
    @Test
    fun `parses the real 3_1_2 capture with v-prefixed dashed and dated versions`() {
        val output = "\u001B[0mchecking for platform ... \u001B[38;5;2;1mmingw (x86_64)\u001B[0m\r\n" +
            "\u001B[0mchecking for Mingw SDK ... \u001B[38;5;2;1mC:\\Program Files\\Git\\mingw64 (nil)\u001B[0m\r\n" +
            "The package names:\r\n" +
            "    zlib: \r\n" +
            "\u001B[0m      -> \u001B[38;5;5;1mzlib-v1.3.2\u001B[0m: A Massively Spiffy Yet Delicately " +
            "Unobtrusive Compression Library (in xmake-repo)\u001B[0m\r\n" +
            "\u001B[0m      -> \u001B[38;5;5;1mzlib-ng-2.3.3\u001B[0m: \u001B[1mzlib\u001B[0m replacement " +
            "with optimizations for next generation systems. (in xmake-repo)\u001B[0m\r\n" +
            "\u001B[0m      -> \u001B[38;5;5;1mzopfli-2021.06.14\u001B[0m: Compression for further " +
            "compression. (in xmake-repo)\u001B[0m\r\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertEquals(
            listOf(
                XMakePackageSearchEntry(
                    "zlib", "v1.3.2",
                    "A Massively Spiffy Yet Delicately Unobtrusive Compression Library", "xmake-repo",
                ),
                XMakePackageSearchEntry(
                    "zlib-ng", "2.3.3",
                    "zlib replacement with optimizations for next generation systems.", "xmake-repo",
                ),
                XMakePackageSearchEntry("zopfli", "2021.06.14", "Compression for further compression.", "xmake-repo"),
            ),
            results.hits,
        )
        // The `checking for ...` prologue is noise, never a diagnosis.
        assertTrue(results.diagnostics.isEmpty())
    }

    /** The fix for the audit's section 2 misalignment: a `v`-prefixed version token splits
     *  off the name instead of gluing to it, so an install built from the hit asks xmake
     *  for `minizip`, not for the nonexistent `minizip-v1.3.2`. */
    @Test
    fun `v-prefixed versions split from the name`() {
        val results = XMakePackageParsers.parseSearchResults("    -> minizip-v1.3.2: Minizip. (in xmake-repo)\n")

        val hit = results.hits.single()
        assertEquals("minizip", hit.name)
        assertEquals("v1.3.2", hit.version)
    }

    @Test
    fun `parses dashed package names and tolerates an empty description`() {
        val output = "    -> mongo-c-driver-1.2.3-beta1:  (in xmake-repo)\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertEquals(1, results.hits.size)
        assertEquals("mongo-c-driver", results.hits[0].name)
        assertEquals("1.2.3-beta1", results.hits[0].version)
        assertNull(results.hits[0].description)
    }

    /** The WSL 2.8.7 capture of docs/roadmap-designs/audit-packages.md, section 1.6: no
     *  unzip/7zip, the error on stdout, exit code 0 - the toolchain's own diagnosis, which
     *  the view must receive instead of reading the answer as a misspelled term. */
    @Test
    fun `a diagnostics-only answer carries the xmake error with zero hits`() {
        val output = "\u001B[0mchecking for platform ... \u001B[38;5;2;1mlinux\u001B[0m\r\n" +
            "\u001B[0mchecking for architecture ... \u001B[38;5;5;1;1mx86_64\u001B[0m\r\n" +
            "\u001B[0m\u001B[1;38;5;1;1merror: \u001B[0mfailed to find unzip or 7zip! please install " +
            "one of them first\u001B[0m\r\n"

        val results = XMakePackageParsers.parseSearchResults(output)

        assertTrue(results.hits.isEmpty())
        assertEquals(
            listOf("error: failed to find unzip or 7zip! please install one of them first"),
            results.diagnostics,
        )
    }

    @Test
    fun `note lines ride out as search diagnostics`() {
        val results = XMakePackageParsers.parseSearchResults(
            "note: try updating the repositories\n    -> tinytest-1.0.0: A tiny test framework (in localrepo)\n",
        )

        assertEquals(listOf("note: try updating the repositories"), results.diagnostics)
        assertEquals(listOf("tinytest"), results.hits.map { it.name })
    }

    @Test
    fun `search junk answers no hits`() {
        val results = XMakePackageParsers.parseSearchResults("nothing matched\n")

        assertTrue(results.hits.isEmpty())
        assertTrue(results.diagnostics.isEmpty())
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
