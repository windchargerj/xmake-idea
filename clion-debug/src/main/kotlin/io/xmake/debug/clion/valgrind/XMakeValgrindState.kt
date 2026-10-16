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
package io.xmake.debug.clion.valgrind

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
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File
import java.nio.file.Path

/**
 * Runs the built target under Valgrind and writes an XML report the IDE's memory view can read.
 * The build is prepared in xmake's valgrind mode (`-m valgrind`, debug symbols included).
 *
 * The launch itself stays entirely on the xmake side: `xmake l runtool.lua` resolves the target
 * and starts valgrind inside the environment xmake runs in, so the executor owns no paths and no
 * host logic of its own.
 */
internal class XMakeValgrindState private constructor(
    environment: ExecutionEnvironment,
    val configureCommand: XMakeCommand,
    val buildCommand: XMakeCommand,
    val toolCommand: XMakeCommand,
    val reportFile: File,
) : CommandLineState(environment), XMakeAnalysisState {

    public override fun startProcess(): ProcessHandler =
        XMakeCommandProcessHandler(
            environment.project,
            toolCommand,
            XMakeConsoleOptions(showExitCode = true),
        ).processHandler

    override suspend fun execute(
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor =
        executeValgrind(environment.project.xmakeExecutionService, this, environment, console)

    /** The run console, kept so the findings summary can print clickable locations into it. */
    internal var runConsole: com.intellij.execution.ui.ConsoleView? = null

    /** Maps a source path the tool reported to its local form: host form first, then project
     *  relative, matching how the toolchain compiles sources. */
    internal fun toLocalSource(file: File): File? {
        val mapped = RemotePathMapper.forToolkit(
            buildCommand.toolkit,
            buildCommand.workingDirectory,
            environment.project,
        ).toLocal(file.toPath())?.toFile()
        if (mapped?.isFile == true) return mapped
        val basePath = environment.project.basePath ?: return null
        return File(basePath, file.path).takeIf(File::isFile)
    }

    /** Maps any path the tool reported, a source file, a directory or a binary, to its local
     *  form; the reported path stays when no mapping exists. */
    internal fun toLocalPath(path: String): String {
        if (path.isEmpty()) return path
        val remote = runCatching { Path.of(path) }.getOrNull() ?: return path
        val mapped = RemotePathMapper.forToolkit(
            buildCommand.toolkit,
            buildCommand.workingDirectory,
            environment.project,
        ).toLocal(remote)
        return mapped?.toString() ?: path
    }

    /**
     * The run console, created once on demand: the native composition wraps it before the run
     * content is built, so it cannot be created inside the execution result.
     */
    internal fun consoleView(executor: Executor): com.intellij.execution.ui.ConsoleView? {
        runConsole?.let { return it }
        return createConsole(executor)?.also { runConsole = it }
    }

    /** The execution result of the run; [console] is the native wrapper when one was built. */
    internal fun createExecutionResult(
        executor: Executor,
        processHandler: ProcessHandler,
        console: com.intellij.execution.ui.ConsoleView? = null,
    ): ExecutionResult {
        val view = console ?: consoleView(executor)
        runConsole = view
        view?.attachToProcess(processHandler)
        return DefaultExecutionResult(
            view,
            processHandler,
            *createActions(view, processHandler, executor),
        )
    }

    companion object {
        fun create(
            configuration: XMakeRunConfiguration,
            environment: ExecutionEnvironment,
        ): XMakeValgrindState {
            val project = configuration.project
            val profile = project.requireXMakeBuildProfileFor(environment.executionTarget)
            val commandFactory = XMakeCommandFactory(project, profile)
            val buildCommand = commandFactory.createTargetBuild(configuration.runTarget)
            if (buildCommand.toolkit.host.type == ToolkitHostType.SSH) {
                throw ExecutionException("XMake Valgrind runs are supported for local and WSL toolkits")
            }
            val reportFile = newReportFile(project)
            return XMakeValgrindState(
                environment = environment,
                configureCommand = commandFactory.createConfigure(modeOverride = VALGRIND_MODE),
                buildCommand = buildCommand,
                toolCommand = commandFactory.createToolRun(
                    targetName = configuration.runTarget,
                    toolArguments = listOf(
                        "valgrind",
                        "--tool=memcheck",
                        "--leak-check=full",
                        "--xml=yes",
                        "--xml-file={report}",
                        "{binary}",
                        *ParametersListUtil.parse(configuration.runArguments).toTypedArray(),
                    ),
                    reportRelativePath = reportFile.toRelativeString(File(project.basePath)),
                    environmentVariables = configuration.runEnvironment,
                ),
                reportFile = reportFile,
            )
        }

        /** Reports go to the project's build directory, which both sides of a WSL disk mount see. */
        private fun newReportFile(project: Project): File {
            val buildDirectory = File(project.basePath ?: ".", "build")
            buildDirectory.mkdirs()
            return File(buildDirectory, "xmake-valgrind-${System.currentTimeMillis()}.xml")
        }

        private const val VALGRIND_MODE = "valgrind"
    }
}
