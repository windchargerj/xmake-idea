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

/**
 * Reads the stdout of `xmake test` and turns it into per-test results.
 *
 * xmake prints two shapes depending on its version, both verified against live runs:
 * 3.x starts every test with `running.test` lines and reports every result in a
 * `report of tests:` section after the run, while 2.8.x prints each result live as the
 * test finishes (its percentages then arrive out of order). One grammar covers both -
 * `[ NN%]: target/test <padding dots> outcome seconds` - plus the one-line summary each
 * version ends with. xmake colors its output even when piped, and Windows runs end
 * lines with carriage returns; both are cleaned before matching.
 */
internal class XMakeTestOutputParser {

    private val buffer = StringBuilder()
    private val results = mutableListOf<XMakeTestResult>()
    private var summary: String? = null
    private var sawFailureLine = false

    fun feed(text: String) {
        buffer.append(text)
        while (true) {
            val breakAt = buffer.indexOfAny(LINE_BREAKS)
            if (breakAt < 0) return
            val rawLine = buffer.substring(0, breakAt)
            buffer.delete(0, breakAt + lineBreakLength(breakAt))
            consumeLine(cleanLine(rawLine))
        }
    }

    fun finish(): XMakeTestRunResults {
        if (buffer.isNotEmpty()) {
            consumeLine(cleanLine(buffer.toString()))
            buffer.setLength(0)
        }
        return XMakeTestRunResults(results.toList(), summary, sawFailureLine || results.any { it.outcome.failed })
    }

    private fun consumeLine(line: String) {
        if (line.isEmpty()) return
        RESULT.matchEntire(line)?.let { match ->
            val (name, outcome, seconds) = match.destructured
            results += XMakeTestResult(name, XMakeTestOutcome.of(outcome), seconds.toDoubleOrNull() ?: 0.0)
            return
        }
        if (SUMMARY.containsMatchIn(line)) {
            summary = line
            return
        }
        if (line.startsWith(ERROR_PREFIX)) sawFailureLine = true
    }

    private fun cleanLine(line: String): String = ANSI.replace(line.trim(), "")

    private fun lineBreakLength(breakAt: Int): Int = when (buffer[breakAt]) {
        '\r' -> if (breakAt + 1 < buffer.length && buffer[breakAt + 1] == '\n') 2 else 1
        else -> 1
    }

    private companion object {
        private val LINE_BREAKS = charArrayOf('\n', '\r')

        /** `[ 50%]: demo/hello .... passed 0.063s`; the dots pad the longest name, and a
         *  2.8.x line pads with spaces first - `[ .]+` reads either, empty dots included. */
        private val RESULT = Regex(
            """\[\s*\d+%]: (\S+)[ .]+(passed|failed|expected failure|unexpected pass) ([\d.]+)s""",
        )

        /** `<P>% tests passed, <F> test(s) failed[, ...] out of <T>, spent <S>s`. */
        private val SUMMARY = Regex("""\d+% tests passed.+\d+(\.\d+)?s$""")

        private const val ERROR_PREFIX = "error:"

        /** xmake colors its output even when piped. */
        private val ANSI = Regex("""[\u001B\u009B]?\[[0-9;]+[A-Za-z]""")
    }
}

/** One test's outcome, as xmake labels it. */
internal enum class XMakeTestOutcome {
    PASSED,
    FAILED,
    EXPECTED_FAILURE,
    UNEXPECTED_PASS;

    val failed: Boolean
        get() = this == FAILED || this == UNEXPECTED_PASS

    companion object {
        fun of(label: String): XMakeTestOutcome = when (label) {
            "passed" -> PASSED
            "failed" -> FAILED
            "expected failure" -> EXPECTED_FAILURE
            else -> UNEXPECTED_PASS
        }
    }
}

internal data class XMakeTestResult(
    /** The `target/test` name xmake reports. */
    val name: String,
    val outcome: XMakeTestOutcome,
    val seconds: Double,
)

internal class XMakeTestRunResults(
    val results: List<XMakeTestResult>,
    /** xmake's own one-line summary, shown under the tree. */
    val summary: String?,
    /** True when any test failed or xmake itself errored; exit codes lie (policies can zero them). */
    val anyFailed: Boolean,
)
