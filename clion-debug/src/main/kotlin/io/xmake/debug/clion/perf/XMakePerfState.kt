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
package io.xmake.debug.clion.perf

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.XMakeAnalysisState
import io.xmake.run.command.xmakeExecutionService
import io.xmake.project.console.XMakeConsole
import com.intellij.execution.ui.RunContentDescriptor
import io.xmake.run.command.XMakeCommandProcessHandler
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File

/**
 * Records a target with Linux `perf`, producing a `perf.data` profile. The build runs in xmake's
 * profile mode (symbols enabled).
 *
 * The recording itself stays entirely on the xmake side: `xmake l runtool.lua` resolves the
 * target and starts perf inside the environment xmake runs in. The script writes the recording
 * to the native temp directory first and copies it into the project build directory afterwards,
 * because perf cannot record onto a WSL-mounted Windows drive.
 */
internal class XMakePerfState private constructor(
    environment: ExecutionEnvironment,
    val configureCommand: XMakeCommand,
    val buildCommand: XMakeCommand,
    val toolCommand: XMakeCommand,
    val dataFile: File,
    val collapsedFile: File,
) : CommandLineState(environment), XMakeAnalysisState {

    public override fun startProcess(): ProcessHandler =
        XMakeCommandProcessHandler(
            environment.project,
            toolCommand,
            XMakeConsoleOptions(showExitCode = true),
        ).processHandler

    /** The run console, kept so the recording guidance can print into it. */
    internal var runConsole: com.intellij.execution.ui.ConsoleView? = null
        private set

    override suspend fun execute(
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor =
        executePerf(environment.project.xmakeExecutionService, this, environment, console)

    internal fun createExecutionResult(
        executor: Executor,
        processHandler: ProcessHandler,
    ): ExecutionResult {
        val console = createConsole(executor)
        runConsole = console
        console?.attachToProcess(processHandler)
        return DefaultExecutionResult(
            console,
            processHandler,
            *createActions(console, processHandler, executor),
        )
    }

    companion object {
        fun create(
            configuration: XMakeRunConfiguration,
            environment: ExecutionEnvironment,
        ): XMakePerfState {
            val project = configuration.project
            val profile = project.requireXMakeBuildProfileFor(environment.executionTarget)
            val commandFactory = XMakeCommandFactory(project, profile)
            val buildCommand = commandFactory.createTargetBuild(configuration.runTarget)
            if (buildCommand.toolkit.host.type == ToolkitHostType.SSH) {
                throw ExecutionException("XMake profile runs are supported for local and WSL toolkits")
            }
            val dataFile = newDataFile(project)
            val collapsedFile = collapsedFileFor(dataFile)
            return XMakePerfState(
                environment = environment,
                configureCommand = commandFactory.createConfigure(modeOverride = PROFILE_MODE),
                buildCommand = buildCommand,
                toolCommand = commandFactory.createToolRun(
                    targetName = configuration.runTarget,
                    toolArguments = listOf(
                        "perf",
                        "record",
                        "--call-graph", "dwarf",
                        "-o", "{report}",
                        "--",
                        "{binary}",
                        *ParametersListUtil.parse(configuration.runArguments).toTypedArray(),
                    ),
                    reportRelativePath = dataFile.toRelativeString(File(project.basePath)),
                    environmentVariables = configuration.runEnvironment,
                    // The script folds the recording into the collapsed-stacks format the IDE
                    // profiler view consumes; the flame graph opens right after the run.
                    extraEnvironment = mapOf(
                        XMakeCommandFactory.RUNTOOL_COLLAPSE_VARIABLE to
                            collapsedFile.toRelativeString(File(project.basePath)).replace('\\', '/'),
                    ),
                ),
                dataFile = dataFile,
                collapsedFile = collapsedFile,
            )
        }

        /** Where the folded stacks for the profiler view land, next to the raw recording. */
        private fun collapsedFileFor(dataFile: File): File =
            File(dataFile.parentFile, "${dataFile.nameWithoutExtension}.collapsed")

        /** Recordings go to the project's build directory, which both sides of a WSL disk mount see. */
        private fun newDataFile(project: Project): File {
            val buildDirectory = File(project.basePath ?: ".", "build")
            buildDirectory.mkdirs()
            return File(buildDirectory, "xmake-perf-${System.currentTimeMillis()}.data")
        }

        private const val PROFILE_MODE = "profile"
    }
}
