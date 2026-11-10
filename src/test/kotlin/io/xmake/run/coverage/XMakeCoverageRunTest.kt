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
package io.xmake.run.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class XMakeCoverageRunTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `gcc family toolchains take the gcov flags`() {
        assertTrue(toolchainAcceptsGcovFlags("gcc"))
        assertTrue(toolchainAcceptsGcovFlags("clang"))
        assertTrue(toolchainAcceptsGcovFlags("mingw"))
        assertTrue(toolchainAcceptsGcovFlags("LLVM"))
        // "default" defers to xmake's per-host resolution, which was gcc-family on every
        // verified host; where it resolves to MSVC the post-build check reports it.
        assertTrue(toolchainAcceptsGcovFlags("default"))
        assertFalse(toolchainAcceptsGcovFlags("msvc"))
    }

    @Test
    fun `the flag route adds one gcov flag per compile and link option`() {
        assertEquals(
            listOf("--cxflags=--coverage", "--ldflags=--coverage", "--shflags=--coverage"),
            coverageConfigureArguments("gcc", ""),
        )
    }

    @Test
    fun `the flags stay away from toolchains without gcov`() {
        assertEquals(emptyList<String>(), coverageConfigureArguments("msvc", ""))
    }

    @Test
    fun `an existing user flag gains the gcov flag instead of being shadowed`() {
        // xmake keeps the last value of a repeated option, so a second --cxflags token
        // would silently drop the user's own flags (verified on 3.1.2).
        assertEquals(
            listOf("--cxflags=-DFOO --coverage", "--ldflags=--coverage", "--shflags=--coverage"),
            coverageConfigureArguments("gcc", "--cxflags=-DFOO"),
        )
    }

    @Test
    fun `a user value already carrying the flag is left alone`() {
        assertEquals(
            listOf("--ldflags=--coverage", "--shflags=--coverage"),
            coverageConfigureArguments("gcc", "--cxflags=--coverage"),
        )
    }

    @Test
    fun `the last of repeated user options wins the merge`() {
        assertEquals(
            listOf("--cxflags=-DBAR --coverage", "--ldflags=--coverage", "--shflags=--coverage"),
            coverageConfigureArguments("gcc", "--cxflags=-DFOO --cxflags=-DBAR"),
        )
    }

    @Test
    fun `quoted user values survive as one token`() {
        // The parse drops the quotes; the merged token rides as one argv entry with an
        // embedded space, which xmake's own option parser splits into the flag values.
        assertEquals(
            listOf("--cxflags=-DFOO -DPRIV --coverage", "--ldflags=--coverage", "--shflags=--coverage"),
            coverageConfigureArguments("gcc", "--cxflags=\"-DFOO -DPRIV\""),
        )
    }

    @Test
    fun `detects the coverage rule in the project's root xmake lua`() {
        val root = tempFolder.root
        File(root, "xmake.lua").writeText("""add_rules("mode.debug", "mode.coverage")""")
        assertTrue(declaresCoverageRule(root.path))
    }

    @Test
    fun `a project without the rule or without xmake lua declares none`() {
        val root = tempFolder.root
        File(root, "xmake.lua").writeText("""add_rules("mode.debug", "mode.release")""")
        assertFalse(declaresCoverageRule(root.path))
        assertFalse(declaresCoverageRule(tempFolder.newFolder("empty").path))
        assertFalse(declaresCoverageRule(null))
    }

    @Test
    fun `a directory with a nested gcda counts as gathered`() {
        val root = tempFolder.newFolder("build")
        val nested = File(root, ".objs/demo/src").apply { mkdirs() }
        File(nested, "main.cpp.gcda").writeText("")
        assertTrue(hasCoverageData(root))
    }

    @Test
    fun `an empty or missing build directory gathers nothing`() {
        val empty = tempFolder.newFolder("empty")
        assertFalse(hasCoverageData(empty))
        assertFalse(hasCoverageData(File(tempFolder.root, "does-not-exist")))
        assertFalse(hasCoverageData(null))
    }

    @Test
    fun `the missing-data message names the stale instrumentation cause and the remedy`() {
        val message = missingCoverageDataMessage(tempFolder.newFolder("build"))
        assertTrue(message.contains(".gcda"))
        assertTrue(message.contains(".gcno"))
        assertTrue(message.contains("stale"))
        assertTrue(message.contains("xmake --clean"))
    }
}
