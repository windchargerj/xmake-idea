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

import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent
import com.intellij.openapi.util.Key

/**
 * Reads `xmake test`'s stdout and drives the standard test tree: one suite per target,
 * one node per test. Both output shapes feed it - 3.x starts tests with `running.test`
 * lines and reports every result after the run, 2.8.x prints each result live - so a
 * result line for a test nobody announced starts the node first, and everything xmake
 * prints that is not a result or a summary flows to the console uncaptured.
 */
internal class XMakeTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    private val openSuites = mutableSetOf<String>()
    private val openTests = mutableSetOf<String>()
    private var summarySeen = false

    override fun processConsistentText(text: String, outputType: Key<*>) {
        val line = text.trim()
        if (line.isEmpty()) return

        RUNNING.matchEntire(line)?.let { match ->
            val (name) = match.destructured
            startSuite(name.substringBefore('/'))
            startTest(name)
            return
        }
        RESULT.matchEntire(line)?.let { match ->
            val (name, outcome, seconds) = match.destructured
            val suite = name.substringBefore('/')
            startSuite(suite)
            if (name !in openTests) startTest(name)
            finishTest(name, outcome, seconds)
            return
        }
        if (SUMMARY.containsMatchIn(line)) {
            summarySeen = true
            fireOnUncapturedOutput("$text\n", outputType)
            return
        }
        fireOnUncapturedOutput(text, outputType)
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        // xmake reports each test before it dies; anything still open was cut short.
        finishOpenNodes()
        super.flushBufferOnProcessTermination(exitCode)
    }

    private fun startSuite(suite: String) {
        if (suite in openSuites) return
        processor?.onSuiteStarted(TestSuiteStartedEvent(suite, suite, ROOT_ID, null, null, null, null, true))
        openSuites += suite
    }

    private fun startTest(name: String) {
        if (name in openTests) return
        processor?.onTestStarted(TestStartedEvent(name, name, name.substringBefore('/'), null, null, null, null, true))
        openTests += name
    }

    private fun finishTest(name: String, outcome: String, seconds: String) {
        val processor = processor ?: return
        val duration = ((seconds.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        when (XMakeTestOutcome.of(outcome)) {
            XMakeTestOutcome.PASSED, XMakeTestOutcome.EXPECTED_FAILURE ->
                processor.onTestFinished(TestFinishedEvent(name, name, duration))
            XMakeTestOutcome.FAILED ->
                processor.onTestFailure(
                    TestFailedEvent(name, name, "$name failed", null, false, null, null, null, null, false, false, duration),
                )
            XMakeTestOutcome.UNEXPECTED_PASS ->
                processor.onTestFailure(
                    TestFailedEvent(
                        name, name, "$name passed though it was expected to fail",
                        null, false, null, null, null, null, false, false, duration,
                    ),
                )
        }
        openTests -= name
    }

    /** Closes every node still open at the end of the stream, suites innermost first. */
    private fun finishOpenNodes() {
        val processor = processor
        if (processor == null) {
            openSuites.clear()
            openTests.clear()
            return
        }
        openTests.clear()
        openSuites.reversed().forEach { suite ->
            processor.onSuiteFinished(TestSuiteFinishedEvent(suite, suite, null))
        }
        openSuites.clear()
    }

    private companion object {
        private const val ROOT_ID = "0"

        /** `[  0%]: running.test demo/hello` - 3.x announces each test as it starts. */
        private val RUNNING = Regex("""\[\s*\d+%]: running\.test (\S+)""")

        /** `[ 37%]: demo/grouped .... passed 0.063s`; the padding dots and spaces read alike. */
        private val RESULT = Regex(
            """\[\s*\d+%]: (\S+)[ .]+(passed|failed|expected failure|unexpected pass) ([\d.]+)s""",
        )

        /** `<P>% tests passed, ... out of <T>, spent <S>s` - xmake's own closing line. */
        private val SUMMARY = Regex("""\d+% tests passed.+\d+(\.\d+)?s$""")
    }
}
