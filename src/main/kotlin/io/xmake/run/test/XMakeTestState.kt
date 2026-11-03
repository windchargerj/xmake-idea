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

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.util.execution.ParametersListUtil
import io.xmake.project.console.FileReferenceFilter
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandProcessHandler
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.target.requireXMakeBuildProfileFor

/**
 * One `xmake test` run under the active build profile. The test command runs like any
 * other XMake command - its console prints the results xmake reports, paths hyperlinked -
 * and per-test outcomes parse from the lines the action prints as it runs them.
 */
internal class XMakeTestState(
    /** The test run configuration the state was built from; its options drive the launch. */
    val configuration: XMakeTestRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    public override fun startProcess(): ProcessHandler =
        XMakeCommandProcessHandler(
            environment.project,
            testCommand(),
            XMakeConsoleOptions(showExitCode = true),
        ).processHandler

    init {
        addConsoleFilters(FileReferenceFilter(environment.project))
    }

    internal fun createExecutionResult(
        executor: Executor,
        processHandler: ProcessHandler,
    ): ExecutionResult {
        // The standard test tree: xmake's own result lines drive it, its uncaptured
        // output stays in the console, and the framework's export and statistics work.
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
            XMakeTestConsoleProperties.FRAMEWORK_NAME,
            processHandler,
            XMakeTestConsoleProperties(configuration, executor),
        )
        return DefaultExecutionResult(
            console,
            processHandler,
            *createActions(console, processHandler, executor),
        )
    }

    private fun testCommand(): XMakeCommand {
        val profile = configuration.project.requireXMakeBuildProfileFor(environment.executionTarget)
        return configuration.project.xmakeCommands(profile).createTestRun(
            filters = ParametersListUtil.parse(configuration.testFilters),
            outputOnFailure = configuration.outputOnFailure,
        )
    }
}
