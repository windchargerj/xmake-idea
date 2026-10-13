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
package io.xmake.run.state

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.util.execution.ParametersListUtil
import io.xmake.debug.XMakeTargetLocation
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.resolveLaunchWorkingDirectory
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File

/**
 * Records a target with Linux `perf`, producing a `perf.data` profile. The build runs with
 * symbols enabled; the binary is launched directly rather than through `xmake run`, so the
 * profile captures the target rather than the build tool.
 */
internal class XMakePerfState private constructor(
    environment: ExecutionEnvironment,
    val targetName: String,
    val configureCommand: XMakeCommand,
    val buildCommand: XMakeCommand,
    val targetPathCommand: XMakeCommand,
    val arguments: List<String>,
    val processEnvironment: Map<String, String>,
    val launchWorkingDirectory: String?,
    val dataFile: File,
) : CommandLineState(environment) {

    /** Set by XMakeRunner after the preparation phase resolved the built binary. */
    var resolvedTarget: XMakeTargetLocation? = null

    public override fun startProcess(): ProcessHandler {
        val target = resolvedTarget
            ?: throw ExecutionException("The XMake target was not built before the profile launch")
        val commandLine = GeneralCommandLine(PERF_EXECUTABLE)
            .withParameters("record")
            .withParameters("--call-graph", "dwarf")
            .withParameters("-o", dataFile.absolutePath)
            .withParameters(target.executableFile.absolutePath)
            .withParameters(arguments)
            .withWorkDirectory(launchWorkingDirectory ?: target.effectiveRunDirectory.absolutePath)
            .withEnvironment(processEnvironment)
        return KillableColoredProcessHandler(commandLine).apply {
            ProcessTerminatedListener.attach(this, environment.project)
        }
    }

    internal fun createExecutionResult(
        executor: Executor,
        processHandler: ProcessHandler,
    ): ExecutionResult {
        val console = createConsole(executor)
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
            if (buildCommand.toolkit.requiresBackend) {
                throw ExecutionException("XMake profile runs are supported only for local toolkits")
            }
            return XMakePerfState(
                environment = environment,
                targetName = configuration.runTarget,
                // The coverage mode builds with `-g`; perf needs the same symbols.
                configureCommand = commandFactory.createConfigure(modeOverride = PROFILE_MODE),
                buildCommand = buildCommand,
                targetPathCommand = commandFactory.createTargetPathQuery(configuration.runTarget),
                arguments = ParametersListUtil.parse(configuration.runArguments),
                processEnvironment = configuration.runEnvironment.envs.toMap(),
                launchWorkingDirectory = resolveLaunchWorkingDirectory(
                    project,
                    buildCommand.toolkit,
                    configuration.launchWorkingDirectory,
                ),
                dataFile = File(System.getProperty("java.io.tmpdir"), "xmake-perf-${System.currentTimeMillis()}.data"),
            )
        }

        private const val PROFILE_MODE = "profile"
        private const val PERF_EXECUTABLE = "perf"
    }
}
