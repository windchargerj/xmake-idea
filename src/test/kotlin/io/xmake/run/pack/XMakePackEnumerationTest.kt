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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.run.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The xpacks.lua payload grammar: `name<TAB>formats` lines between markers, anything xmake
 *  logs ahead of the payload ignored. */
class XMakePackEnumerationTest {

    @Test
    fun `parses the payload with warnings logged ahead of the markers`() {
        val output = """
            checking for the platform ... windows (x64)
            warning: something xmake logs first
            __begin__
            hello\tzip,nsis
            all\tzip,tar.gz,runself
            bare\t
            __end__
        """.trimIndent().replace("\\t", "\t") + "\n"

        val entries = XMakePackEnumeration.parse(output)

        assertEquals(
            listOf(
                XMakePackEntry("hello", listOf("zip", "nsis")),
                XMakePackEntry("all", listOf("zip", "tar.gz", "runself")),
                XMakePackEntry("bare", emptyList()),
            ),
            entries,
        )
    }

    @Test
    fun `an output without markers answers nothing`() {
        assertTrue(XMakePackEnumeration.parse("no payload here").isEmpty())
        assertTrue(XMakePackEnumeration.parse("").isEmpty())
    }

    @Test
    fun `an empty payload answers nothing`() {
        assertTrue(XMakePackEnumeration.parse("__begin__\n__end__").isEmpty())
    }

    @Test
    fun `malformed lines are skipped and duplicates collapse`() {
        val output = "__begin__\nno-tab-line\n\tformats-only\nduped\tzip\nok\tzip, zip\n__end__\n"

        val entries = XMakePackEnumeration.parse(output)

        assertEquals(listOf(XMakePackEntry("duped", listOf("zip")), XMakePackEntry("ok", listOf("zip"))), entries)
    }
}
