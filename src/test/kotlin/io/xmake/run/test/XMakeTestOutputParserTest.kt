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
package io.xmake.run.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XMakeTestOutputParserTest {

    @Test
    fun `reads the 3x report section with its outcomes and summary`() {
        val parser = XMakeTestOutputParser()
        parser.feed(
            """
            running tests ...
            [  0%]: running.test demo/expectfail
            [  0%]: running.test demo/fail_test
            report of tests:
            [ 12%]: demo/expectfail . expected failure 0.110s
            [ 25%]: demo/fail_test .. failed 0.079s
            [ 37%]: demo/grouped .... passed 0.063s
            [ 50%]: demo/hello ...... passed 0.063s
            [100%]: demo/slow ....... passed 0.625s

            Detailed summary:
            Failed tests:
             - demo/fail_test
            Expected failures:
             - demo/expectfail

            60% tests passed, 1 test(s) failed, 1 expected failure(s) out of 5, spent 0.781s
            error: <no error object>
            """.trimIndent() + "\n",
        )
        val run = parser.finish()

        assertEquals(5, run.results.size)
        assertEquals(XMakeTestOutcome.EXPECTED_FAILURE, run.results[0].outcome)
        assertFalse(run.results[0].outcome.failed)
        assertEquals(XMakeTestOutcome.FAILED, run.results[1].outcome)
        assertTrue(run.results[1].outcome.failed)
        assertEquals(0.625, run.results[4].seconds, 1e-9)
        assertTrue(run.anyFailed)
        assertTrue(run.summary!!.startsWith("60% tests passed"))
    }

    @Test
    fun `reads the 28x live lines, out-of-order percentages and all`() {
        val parser = XMakeTestOutputParser()
        parser.feed(
            """
            running tests ...
            [ 12%]: demo/expectfail  .................................... failed 0.012s
            [100%]: other/other_pass .................................... passed 0.003s
            [ 87%]: demo/slow        .................................... passed 0.608s

            67% tests passed, 1 tests failed out of 3, spent 0.619s
            """.trimIndent() + "\n",
        )
        val run = parser.finish()

        assertEquals(3, run.results.size)
        assertEquals("other/other_pass", run.results[1].name)
        assertEquals(XMakeTestOutcome.PASSED, run.results[1].outcome)
        assertTrue(run.anyFailed)
    }

    @Test
    fun `strips the colors xmake emits even when piped`() {
        val escape = "\u001B[0m"
        val green = "\u001B[38;5;2;1m"
        val red = "\u001B[38;5;1;1m"
        val parser = XMakeTestOutputParser()
        parser.feed("${green}[100%]: demo/hello ....... passed 0.063s$escape\r\n")
        parser.feed("${red}[ 50%]: demo/bad ......... failed 0.001s$escape\r\n")
        val run = parser.finish()

        assertEquals(2, run.results.size)
        assertEquals("demo/hello", run.results[0].name)
        assertEquals(XMakeTestOutcome.PASSED, run.results[0].outcome)
    }

    @Test
    fun `a passing run reads as not failed`() {
        val parser = XMakeTestOutputParser()
        parser.feed("[100%]: demo/hello . passed 0.001s\n100% tests passed, 0 tests failed out of 1, spent 0.001s\n")
        val run = parser.finish()

        assertFalse(run.anyFailed)
        assertEquals(1, run.results.size)
    }

    @Test
    fun `an unexpected pass is its own outcome and counts as failed`() {
        // xmake's fourth label, one the grammar rotates explicitly; without it a wrongly
        // passing test would vanish from the tree or fall into the summary's else branch.
        val parser = XMakeTestOutputParser()
        parser.feed("[100%]: demo/flaky ......... unexpected pass 0.002s\n")
        val run = parser.finish()

        assertEquals(1, run.results.size)
        assertEquals(XMakeTestOutcome.UNEXPECTED_PASS, run.results[0].outcome)
        assertTrue(run.results[0].outcome.failed)
        assertTrue(run.anyFailed)
    }

    @Test
    fun `chunk boundaries never split a result line`() {
        val text = "[100%]: demo/hello ......... passed 0.001s\n"
        val parser = XMakeTestOutputParser()
        // feed in 7-character pieces: the same buffered reassembly the sanitizer parser
        // is tested on, which this parser shares the shape of
        var index = 0
        while (index < text.length) {
            parser.feed(text.substring(index, minOf(index + 7, text.length)))
            index += 7
        }
        val run = parser.finish()

        assertEquals(1, run.results.size)
        assertEquals("demo/hello", run.results[0].name)
        assertEquals(XMakeTestOutcome.PASSED, run.results[0].outcome)
    }
}
