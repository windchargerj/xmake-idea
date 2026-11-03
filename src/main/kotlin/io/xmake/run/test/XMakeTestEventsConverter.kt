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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent
import com.intellij.openapi.util.Key

/**
 * Reads `xmake test`'s stdout and drives the standard test tree: one suite per target,
 * one node per test. Both output shapes feed it - 3.x starts tests with `running.test`
 * lines and reports every result after the run, 2.8.x prints each result live - so a
 * result line for a test nobody announced starts the node first. Everything xmake
 * prints that is not a result or a summary still flows to the console uncaptured; a
 * copy also lands in a test node, and where that copy goes depends on the shape:
 * 3.x prints a test's output inside its `running.test` window, so while that test is
 * open every uncaptured line is copied into its node; 2.8.x announces nothing, so
 * lines captured between two result lines are held back, and when the next result
 * reports a failure for a test nobody announced, they are fed into that node before
 * the failure event - the node then shows the cause above the verdict.
 *
 * The attribution is deliberately conservative. It assumes xmake runs tests one at a
 * time - a parallel run interleaves outputs that cannot be split, and they all land
 * on the one open window. A passed test's held-back output is dropped rather than
 * attached (the console keeps it), the buffer keeps only its tail because only a
 * failure ever reads it, and output xmake defers until after a result line - 3.x's
 * report section - stays console-only, because nothing marks the test it belongs to.
 */
internal class XMakeTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    private val openSuites = mutableSetOf<String>()
    private val openTests = mutableSetOf<String>()
    private var summarySeen = false

    /** The test the last `running.test` line announced and no result has followed: 3.x's
     *  live window, during which whatever xmake prints belongs to that test. */
    private var runningTest: String? = null

    /** Output seen while no window was open, awaiting the result line that owns it. */
    private val pendingOutput = ArrayDeque<BufferedOutput>()

    private data class BufferedOutput(val text: String, val stdout: Boolean)

    override fun processConsistentText(text: String, outputType: Key<*>) {
        val line = text.trim()
        if (line.isEmpty()) return

        RUNNING.matchEntire(line)?.let { match ->
            val (name) = match.destructured
            startSuite(name.substringBefore('/'))
            startTest(name)
            runningTest = name
            pendingOutput.clear()
            return
        }
        RESULT.matchEntire(line)?.let { match ->
            val (name, outcome, seconds) = match.destructured
            startSuite(name.substringBefore('/'))
            val announced = name in openTests
            if (!announced) {
                startTest(name)
                // 2.8.x's result line is the first word about the test; what it printed
                // while running has been waiting for exactly this node.
                if (XMakeTestOutcome.of(outcome).failed) flushPendingOutput(name)
            }
            finishTest(name, outcome, seconds)
            runningTest = null
            pendingOutput.clear()
            return
        }
        if (SUMMARY.containsMatchIn(line)) {
            summarySeen = true
            pendingOutput.clear()
            fireOnUncapturedOutput("$text\n", outputType)
            return
        }
        if (line.startsWith(REPORT_BANNER)) {
            // 3.x opens its report section right after the last `running.test` line: the
            // announcements are over, and nothing below belongs to an open window anymore.
            runningTest = null
            pendingOutput.clear()
            fireOnUncapturedOutput(text, outputType)
            return
        }
        fireOnUncapturedOutput(text, outputType)
        copyUncapturedOutputToNode(text, outputType)
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

    /** Routes one uncaptured line's copy into the tree: into the running test's node when
     *  3.x's window is open, otherwise into the buffer 2.8.x's next result line reads. */
    private fun copyUncapturedOutputToNode(text: String, outputType: Key<*>) {
        val stdout = ProcessOutputType.isStdout(outputType)
        val live = runningTest?.takeIf { it in openTests }
        if (live != null) {
            processor?.onTestOutput(TestOutputEvent(live, live, text, stdout))
            return
        }
        pendingOutput += BufferedOutput(text, stdout)
        while (pendingOutput.size > MAX_PENDING_OUTPUT) pendingOutput.removeFirst()
    }

    /** Emits the buffered output as [name]'s node output; the failure event that follows
     *  puts the verdict under the cause it explains. */
    private fun flushPendingOutput(name: String) {
        val processor = processor
        if (processor != null) {
            pendingOutput.forEach { chunk ->
                processor.onTestOutput(TestOutputEvent(name, name, chunk.text, chunk.stdout))
            }
        }
        pendingOutput.clear()
    }

    private companion object {
        private const val ROOT_ID = "0"

        /** Only a failure reads the buffer, and only its tail tells it anything; the cap
         *  keeps a long between-tests stream (e.g. a rebuild) from growing without bound. */
        private const val MAX_PENDING_OUTPUT = 200

        /** `[  0%]: running.test demo/hello` - 3.x announces each test as it starts. */
        private val RUNNING = Regex("""\[\s*\d+%]: running\.test (\S+)""")

        /** `[ 37%]: demo/grouped .... passed 0.063s`; the padding dots and spaces read alike. */
        private val RESULT = Regex(
            """\[\s*\d+%]: (\S+)[ .]+(passed|failed|expected failure|unexpected pass) ([\d.]+)s""",
        )

        /** `<P>% tests passed, ... out of <T>, spent <S>s` - xmake's own closing line. */
        private val SUMMARY = Regex("""\d+% tests passed.+\d+(\.\d+)?s$""")

        /** 3.x heads its deferred result section with `report of tests:`; the live window
         *  is over once it appears, so the banner stays out of the last test's node. */
        private const val REPORT_BANNER = "report of tests"
    }
}
