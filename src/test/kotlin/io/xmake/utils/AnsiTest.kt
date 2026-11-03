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
package io.xmake.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one ANSI strip's behavior matrix: what every output parser shares. */
class AnsiTest {

    @Test
    fun `strips sequences with an escape introducer, zero parameters included`() {
        assertEquals("x", stripAnsi("\u001B[mx"))
        assertEquals("x", stripAnsi("\u001B[1;31mx"))
    }

    @Test
    fun `strips sequences with the 8-bit csi introducer`() {
        assertEquals("x", stripAnsi("\u009B[0mx"))
    }

    @Test
    fun `strips bare fragments whose escape byte a capture path lost`() {
        assertEquals("x", stripAnsi("[1;31mx"))
    }

    @Test
    fun `keeps bracketed prose intact - check names and plain text`() {
        assertEquals(
            "warning: use nullptr [modernize-use-nullptr]",
            stripAnsi("warning: use nullptr [modernize-use-nullptr]"),
        )
        assertEquals("[a] link", stripAnsi("[a] link"))
        // digits without a final letter are prose, not a sequence
        assertEquals("array[0]", stripAnsi("array[0]"))
    }
}
