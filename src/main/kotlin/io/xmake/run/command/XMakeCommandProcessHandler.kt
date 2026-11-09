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
package io.xmake.run.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.pty4j.PtyProcessBuilder
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.shared.XMakeProblem
import io.xmake.utils.SystemUtils.parseProblem
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the OS process of one XMake command. Construction starts the process immediately
 * and may throw ExecutionException, so callers must consume the handler right away,
 * through [awaitSuccessfulCompletion] or the platform's `startProcess` lifecycle.
 */
class XMakeCommandProcessHandler(
    private val project: Project,
    private val command: XMakeCommand,
    private val options: XMakeConsoleOptions = XMakeConsoleOptions(),
    private val onTextAvailable: (String, Key<*>) -> Unit = { _, _ -> },
    private val onProblems: (List<XMakeProblem>) -> Unit = {},
    private val onTerminated: () -> Unit = {},
) {
    private val stdErr = StringBuilder()
    private val stdOut = StringBuilder()

    val processHandler: ProcessHandler = createProcessHandler()

    private val remotePathMapper = RemotePathMapper.forToolkit(command.toolkit, command.workingDirectory, project)

    suspend fun awaitSuccessfulCompletion() {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            val listener = object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    processHandler.removeProcessListener(this)
                    if (!completed.compareAndSet(false, true)) return

                    if (event.exitCode == 0) {
                        continuation.resume(Unit)
                    } else {
                        // xmake prints its version refusal to stdout, so both streams feed
                        // the captured output.
                        val output = listOf(stdErr.toString(), stdOut.toString())
                            .map(String::trim).filter(String::isNotEmpty).joinToString("\n")
                        continuation.resumeWithException(
                            XMakeCommandException(
                                event.exitCode,
                                command.commandLine.commandLineString,
                                output,
                            ),
                        )
                    }
                }
            }
            processHandler.addProcessListener(listener)
            continuation.invokeOnCancellation {
                processHandler.removeProcessListener(listener)
                if (completed.compareAndSet(false, true)) terminateProcess()
            }

            try {
                processHandler.startNotify()
            } catch (error: Throwable) {
                processHandler.removeProcessListener(listener)
                terminateProcess()
                if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
    }

    private fun createProcessHandler(): ProcessHandler {
        val process = try {
            if (options.emulateTerminal && command.toolkit.host.type == ToolkitHostType.LOCAL) {
                createPtyProcess()
            } else {
                command.createProcess(project)
            }
        } catch (error: Exception) {
            throw ExecutionException("Failed to start XMake command: ${command.commandLine.commandLineString}", error)
        }
        return try {
            val handler = if (options.preserveAnsiEscapes) {
                KillableProcessHandler(process, command.commandLine.commandLineString, Charsets.UTF_8)
            } else {
                KillableColoredProcessHandler(process, command.commandLine.commandLineString, Charsets.UTF_8)
            }
            if (options.emulateTerminal && command.toolkit.host.type == ToolkitHostType.LOCAL) {
                handler.setHasPty(true)
            }

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when {
                        ProcessOutputType.isStderr(outputType) -> stdErr.append(event.text)
                        ProcessOutputType.isStdout(outputType) -> stdOut.append(event.text)
                    }
                    onTextAvailable(event.text, outputType)
                }
            })

            if (options.showProblems) {
                val output = StringBuilder()
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) {
                            output.append(event.text)
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        onProblems(parseProblems(output))
                    }
                })
            }
            if (options.showExitCode) {
                ProcessTerminatedListener.attach(handler, project)
            }
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    onTerminated()
                }
            })
            handler
        } catch (error: Throwable) {
            // The handler may not be fully constructed, so force-kill the started process.
            runCatching { process.destroyForcibly() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw ExecutionException("Failed to prepare XMake command: ${command.commandLine.commandLineString}", error)
        }
    }

    private fun terminateProcess() {
        // Graceful destroy; the setup-failure path uses destroyForcibly instead.
        if (processHandler.isProcessTerminated || processHandler.isProcessTerminating) return
        runCatching { processHandler.destroyProcess() }
    }

    private fun parseProblems(output: CharSequence): List<XMakeProblem> {
        val path = try {
            Path.of(command.workingDirectory)
        } catch (_: InvalidPathException) {
            null
        }

        val plainOutput = StringBuilder()
        AnsiEscapeDecoder().escapeText(output.toString(), ProcessOutputTypes.STDOUT) { text, _ ->
            plainOutput.append(text)
        }
        return plainOutput.split(LINE_BREAK_REGEX)
            .mapNotNull { parseProblem(it.trim(), path) }
            .map(::withNavigablePath)
    }

    private fun withNavigablePath(problem: XMakeProblem): XMakeProblem {
        val remote = problem.resolvedPath ?: return problem
        val navigable = remotePathMapper.toLocal(remote) ?: return problem
        return problem.copy(navigablePath = navigable)
    }

    private fun createPtyProcess(): Process {
        val commandLine = command.commandLine
        // pty4j replaces the process environment wholesale, unlike GeneralCommandLine's
        // toProcessBuilder (which updates the inherited map). Inherit the IDE's environment
        // first, then overlay the variables configured on the run configuration; without this,
        // USERPROFILE/HOME/PATH are missing and xmake resolves its config directory wrongly.
        val environment = HashMap(System.getenv())
        environment.putAll(commandLine.environment)
        Log.info("Creating PTY process: ${commandLine.commandLineString} (cwd=${commandLine.workDirectory})")
        return PtyProcessBuilder()
            .setCommand(arrayOf(commandLine.exePath) + commandLine.parametersList.list.toTypedArray())
            .setEnvironment(environment)
            .setDirectory(commandLine.workDirectory?.path)
            .setConsole(false)
            .start()
    }

    private companion object {
        val LINE_BREAK_REGEX = Regex("\\r\\n|\\n|\\r")
    }
}

private val Log = logger<XMakeCommandProcessHandler>()

/**
 * One failed XMake command; the message stays short because [output] was already streamed
 * live by the console, except on the captured path, which reads [output] itself.
 *
 * [summary] is the failure's own sentence - read once by whatever narrates it (the
 * details pane's failure record, a balloon), so every surface says one failure the same
 * way. The wording comes from xmake's stable output texts, each captured live on 2.8.7
 * and 3.1.2. [versionRefusal] is the same output read as the one specialization any
 * caller acts on: the project demands a newer xmake than the toolkit runs.
 */
internal class XMakeCommandException(
    val exitCode: Int,
    commandLine: String,
    val output: String,
) : ExecutionException("XMake command failed with exit code $exitCode (command: $commandLine)") {

    /** The sentence this failure reads aloud. */
    val summary: String = sentence()

    /** Reads the output as the failure's sentence. The refusal names flags in its
     *  explanation, so it wins over the flag pattern. */
    private fun sentence(): String {
        REFUSAL.find(output)?.let { return "this project asks for xmake ${it.groupValues[1]} or newer" }
        INVALID_FLAG.find(output)?.let { match ->
            val flag = match.groupValues[1]
            return "'$flag' is newer than this toolkit - ${flag.trimStart('-')} needs a newer xmake"
        }
        NOT_FOUND.find(output)?.let { return "no installed package matches '${it.groupValues[1]}' on this platform and configuration" }
        if (AUTH_REQUIRED.containsMatchIn(output)) {
            return "xmake is asking for the service password on stdin, which no command channel can answer - store the token first"
        }
        return "xmake exited with code $exitCode"
    }

    /** The xmake version the project demands, when the failure is the interpreter's
     *  version refusal; null for every other failure. */
    val versionRefusal: String? = REFUSAL.find(output)?.groupValues?.get(1)

    private companion object {
        /** xmake 2.8.7 and 3.x, identical text: "xmake v3.1.2 < v3.2.1, please run …". */
        private val REFUSAL = Regex("""xmake v\S+ < v(\S+), please run""")

        /** Live on 2.8.7: `error: Invalid flag: --check`. */
        private val INVALID_FLAG = Regex("""error: Invalid flag: (--?\S+)""")

        /** Live on 2.8.7: `packages(zstd 1.5.6) not found, maybe they don't exactly
         *  match the configuration`; the prefix is all the sentence needs. */
        private val NOT_FOUND = Regex("""packages\((.+?)\) not found""")

        /** xmake 2.8.7 `private/service/remote_cache/client.lua`: "Please input user
         *  <user> password to connect <host:port>:". */
        private val AUTH_REQUIRED = Regex("""Please input user \S+ password to connect""")
    }
}
