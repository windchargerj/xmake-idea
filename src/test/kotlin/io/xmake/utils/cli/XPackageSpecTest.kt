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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grammar truths the spec value type owns, against xmake's own require parser:
 *  the space is the version separator, `@` is a repository separator and travels
 *  inside the name, `::` prefixes ride along. */
class XPackageSpecTest {

    @Test
    fun `a bare name renders as itself`() {
        assertEquals("zlib", XPackageSpec("zlib").render())
        assertEquals("zlib", XPackageSpec.parse("zlib").render())
    }

    @Test
    fun `the pin is a space and nothing else`() {
        assertEquals("zstd 1.5.6", XPackageSpec("zstd", "1.5.6").render())
        assertEquals("rsync 3.4.1", XPackageSpec.parse("rsync 3.4.1").render())
    }

    @Test
    fun `a range ask keeps its spaces verbatim`() {
        val spec = XPackageSpec.parse("tbox >=1.5.1 <1.6.0")
        assertEquals("tbox", spec.name)
        assertEquals(">=1.5.1 <1.6.0", spec.ask)
        assertEquals("tbox >=1.5.1 <1.6.0", spec.render())
    }

    @Test
    fun `an external prefix and a repository qualifier travel in the name`() {
        assertEquals("vcpkg::zlib", XPackageSpec.parse("vcpkg::zlib").name)
        assertNull(XPackageSpec.parse("vcpkg::zlib").ask)
        // The landmine the spec exists to defuse: @ is xmake's repository separator,
        // so a caller who means a version never gets one parsed out of it.
        assertEquals("zstd@1.5.6", XPackageSpec.parse("zstd@1.5.6").name)
        assertNull(XPackageSpec.parse("zstd@1.5.6").ask)
    }

    @Test
    fun `pinnedTo copies with the exact ask, blank asks keep the original`() {
        assertEquals(XPackageSpec("zstd", "1.5.6"), XPackageSpec("zstd").pinnedTo("1.5.6"))
        assertEquals(XPackageSpec("zstd"), XPackageSpec("zstd").pinnedTo(""))
        assertEquals(XPackageSpec("zstd"), XPackageSpec("zstd").pinnedTo(null))
    }

    @Test
    fun `parsing and rendering round-trip the space grammar`() {
        val asks = listOf(
            "zlib",
            "zstd 1.5.6",
            "tbox >=1.5.1 <1.6.0",
            "openssl 1.1.1-w",
            "vcpkg::pcre2/libpcre2-8",
            "nlohmann_json v3.11.3",
        )
        asks.forEach { raw ->
            assertEquals(raw, XPackageSpec.parse(raw).render())
        }
    }

    @Test
    fun `a blank or whitespace name is refused`() {
        try {
            XPackageSpec("  ")
            throw AssertionError("blank name accepted")
        } catch (_: IllegalArgumentException) {
        }
        try {
            XPackageSpec("zstd 1.5.6")
            throw AssertionError("whitespace inside the name accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}
