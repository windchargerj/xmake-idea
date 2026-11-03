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
package io.xmake.run.pack

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.xmake.project.console.FileReferenceFilter
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandProcessHandler
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.target.requireXMakeBuildProfileFor
import io.xmake.utils.notify
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One `xmake pack` run under the active build profile. The command runs like any other
 * XMake command - its console prints xmake's own progress and errors, paths hyperlinked -
 * and every `packing <path> ..` line is collected, mapped to the IDE-visible path, and
 * offered again by the console's reveal action.
 */
internal class XMakePackState(
    /** The pack run configuration the state was built from; its options drive the launch. */
    val configuration: XMakePackRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    /** The pack command; its toolkit and working directory anchor the artifact mapping. */
    private val packCommand: XMakeCommand

    /** The profile's configure pass; with `--autobuild=n` it runs first so the pack still
     *  sees the profile's mode/platform/toolchain (docs/roadmap-designs/packaging.md, §5.2). */
    internal val configureCommand: XMakeCommand

    private val remotePathMapper: RemotePathMapper

    /** The artifacts xmake reported packing, already mapped to IDE-visible local paths, in
     *  print order. Written from the process output thread while the run streams, read from
     *  the EDT by [PackRevealAction]. */
    private val mappedArtifacts = CopyOnWriteArrayList<Path>()

    /** A `packing` line may arrive split across output chunks, so the collector only parses
     *  complete lines and keeps the tail here for the next chunk or the termination flush. */
    private val pendingLine = StringBuilder()

    init {
        val profile = configuration.project.requireXMakeBuildProfileFor(environment.executionTarget)
        val commandFactory = configuration.project.xmakeCommands(profile)
        packCommand = commandFactory.createPack(
            packName = configuration.packName,
            formats = configuration.selectedFormats(),
            outputDirectory = configuration.outputDirectory.trim(),
            basename = configuration.basename.trim(),
            autoBuild = configuration.autoBuild,
            extraArguments = ParametersListUtil.parse(configuration.extraArguments),
        )
        configureCommand = commandFactory.createConfigure()
        remotePathMapper = RemotePathMapper.forToolkit(
            packCommand.toolkit,
            packCommand.workingDirectory,
            environment.project,
        )
        addConsoleFilters(
            FileReferenceFilter(environment.project),
            PackOutputFilter(environment.project, packCommand.workingDirectory, remotePathMapper),
        )
    }

    public override fun startProcess(): ProcessHandler =
        XMakeCommandProcessHandler(
            environment.project,
            packCommand,
            XMakeConsoleOptions(showExitCode = true),
            onTextAvailable = { text, _ -> collectPackedArtifacts(text) },
            onTerminated = { collectPackedArtifacts("\n") },
        ).processHandler

    internal fun createExecutionResult(
        executor: Executor,
        processHandler: ProcessHandler,
    ): ExecutionResult {
        // The plain run console - pack has no test tree for the SM runner to drive.
        val console = createConsole(executor)
        console?.attachToProcess(processHandler)
        return DefaultExecutionResult(
            console,
            processHandler,
            *createActions(console, processHandler, executor) + PackRevealAction(environment.project, this),
        )
    }

    /** Consumes whole lines from the stream and maps every printed artifact once. */
    private fun collectPackedArtifacts(text: String) {
        pendingLine.append(text)
        var newline = pendingLine.indexOf("\n")
        while (newline >= 0) {
            val line = pendingLine.substring(0, newline)
            pendingLine.delete(0, newline + 1)
            collectArtifact(line)
            newline = pendingLine.indexOf("\n")
        }
    }

    private fun collectArtifact(line: String) {
        val printed = PackOutputFilter.parsePackingPath(line) ?: return
        val hostPath = PackOutputFilter.anchorHostPath(printed, packCommand.workingDirectory) ?: return
        // Paths the mapper refuses (an SSH artifact outside the sync root) have no IDE-visible
        // form, so they are simply absent from the reveal action instead of failing a click.
        remotePathMapper.toLocal(hostPath)?.let(mappedArtifacts::addIfAbsent)
    }

    /** Reveals what the run packed in the file manager, under the IDE-visible path. */
    private class PackRevealAction(
        private val project: Project,
        private val state: XMakePackState,
    ) : DumbAwareAction(
        "Reveal Artifact",
        "Show the packed artifact in the file manager",
        AllIcons.Actions.Preview,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val count = state.mappedArtifacts.size
            event.presentation.isEnabledAndVisible = count > 0
            event.presentation.text = if (count > 1) "Reveal $count Artifacts" else "Reveal Artifact"
        }

        override fun actionPerformed(event: AnActionEvent) {
            val artifacts = state.mappedArtifacts.toList()
            artifacts.forEach { artifact ->
                try {
                    RevealFileAction.openFile(artifact)
                } catch (error: Exception) {
                    project.notify(
                        "Failed to reveal the packed artifact",
                        "'${StringUtil.escapeXmlEntities(artifact.toString())}': " +
                            StringUtil.escapeXmlEntities(error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }
    }
}
