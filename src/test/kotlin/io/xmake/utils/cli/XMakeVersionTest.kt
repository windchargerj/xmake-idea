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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capability matrix's truths, at the version boundaries verified live: 2.8.7 on
 *  WSL rejects `--download`/`--check` and answers `--scan`; 3.1.2 answers all of them,
 *  `--info --format=json` included. */
class XMakeVersionTest {

    @Test
    fun `versions parse with suffixes stripped and order correctly`() {
        // Equality reads the numbers, not the display noise the raw string carried.
        assertEquals("3.1.2", XMakeVersion.parse("v3.1.2+HEAD.5d21e9daf").display)
        assertEquals("2.8.7", XMakeVersion.parse("2.8.7").display)
        assertEquals("3.1.0", XMakeVersion.parse("v3.1").display)
        assertEquals(XMakeVersion.UNKNOWN.display, XMakeVersion.parse("the version is a mystery").display)
        assertTrue(XMakeVersion.parse("2.8.7") < XMakeVersion.parse("3.1.2"))
        assertTrue(XMakeVersion.parse("3.1.2") >= XMakeVersion.parse("3.1.2"))
    }
}
