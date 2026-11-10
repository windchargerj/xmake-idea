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
 *
 * @author      ruki
 * @file        XMakeRunner.kt
 *
 */
package io.xmake.run

import com.intellij.coverage.CoverageExecutor
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import io.xmake.debug.XMakeDebugSupport
import io.xmake.debug.prepareXMakeDebugBuild
import io.xmake.debug.prepareXMakeDebugLaunch
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.project.toolkit.XMakeVersionRefusalException
import io.xmake.utils.info.XMakeInfoManager
import io.xmake.project.console.XMakeConsole
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutor
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.coverage.prepareCoverageRun
import io.xmake.run.coverage.reportCoverageData
import io.xmake.run.sanitizer.SanitizerOutputCollector
import io.xmake.run.sanitizer.clearSanitizerFindings
import io.xmake.run.sanitizer.prepareSanitizerRun
import io.xmake.run.sanitizer.publishAnalysisReport
import io.xmake.run.sanitizer.readSanitizerLogFiles
import io.xmake.run.sanitizer.rememberSanitizedRun
import io.xmake.run.sanitizer.sanitizer
import io.xmake.run.pack.XMakePackRunConfiguration
import io.xmake.run.pack.XMakePackState
import io.xmake.run.state.XMakeDebugState
import io.xmake.run.state.XMakeRunState
import io.xmake.run.test.XMakeTestRunConfiguration
import io.xmake.run.test.XMakeTestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.toPromiseWithoutLogError

class XMakeRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun canRun(executorId: String, profile: RunProfile): Boolean = when (profile) {
        is XMakeTestRunConfiguration -> executorId == DefaultRunExecutor.EXECUTOR_ID
        is XMakePackRunConfiguration -> executorId == DefaultRunExecutor.EXECUTOR_ID
        is XMakeRunConfiguration -> when (executorId) {
            DefaultRunExecutor.EXECUTOR_ID -> true
            DefaultDebugExecutor.EXECUTOR_ID -> XMakeDebugSupport.isAvailable()
            CoverageExecutor.EXECUTOR_ID -> true
            else -> XMakeAnalysisSupport.forExecutor(executorId) != null
        }
        else -> false
    }

    override fun getRunnerId(): String = "XMakeRunner"

    override fun execute(
        environment: ExecutionEnvironment,
        state: RunProfileState,
    ): Promise<RunContentDescriptor?> {
        val project = environment.project
        val executor = project.xmakeExecutor
        val deferred = when (state) {
            is XMakeRunState -> executor.submit {
                val console = prepareConsole(project)
                executeRun(executor, state, environment, console)
            }
            is XMakeTestState -> executor.submit {
                val console = prepareConsole(project)
                executeTestRun(state, environment, console)
            }
            is XMakePackState -> executor.submit {
                val console = prepareConsole(project)
                executePackRun(executor, state, environment, console)
            }
            is XMakeDebugState -> executor.submitAfter(
                before = { prepareXMakeDebugBuild(project, state) },
                task = { executeDebug(executor, state, environment) },
            )
            is XMakeAnalysisState -> executor.submit {
                val console = prepareConsole(project)
                state.execute(environment, console)
            }
            else -> executor.submit {
                throw ExecutionException("Unsupported XMake run profile state: ${state::class.java.name}")
            }
        }
        return deferred.toPromiseWithoutLogError()
    }

    private suspend fun executeRun(
        executor: XMakeExecutor,
        state: XMakeRunState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        // Coverage reconfigures with -m coverage and rebuilds, attaches the suite to the
        // configuration before the target starts, and activates it once the target exits.
        val isCoverage = environment.executor.id == CoverageExecutor.EXECUTOR_ID
        // A sanitized run reconfigures with its xmake mode, rebuilds, then collects the
        // runtime's stderr findings while the target runs.
        val sanitizer = state.configuration.sanitizer
        when {
            isCoverage -> prepareCoverageRun(state.configuration, environment, executor, console)
            sanitizer != null -> {
                prepareSanitizerRun(executor, state, environment, console, sanitizer)
                environment.runnerAndConfigurationSettings?.let {
                    rememberSanitizedRun(environment.project, it)
                }
            }
            else -> executor.execute(
                console, state.configureCommand,
                XMakeConsoleOptions(showConsole = false, showOnFailure = true),
            )
        }
        if (sanitizer == null) {
            // A plain run clears the analysis view's current report: its findings describe
            // the last run. The history stays - an ordinary run must not wipe the collected
            // session the Accumulate switch unions; only the view's Clear drops that.
            clearSanitizerFindings(environment.project)
        }

        val findings = sanitizer?.let(::SanitizerOutputCollector)
        val processHandler = state.startProcess(
            onTextAvailable = { text, outputType ->
                if (com.intellij.execution.process.ProcessOutputType.isStderr(outputType)) findings?.append(text)
            },
            onTerminated = {
                if (isCoverage) {
                    reportCoverageData(state.configuration, environment, console)
                }
                if (sanitizer != null && findings != null) {
                    // The mirror files exist only where the plugin could read them back:
                    // a local run's files complete what the live stderr lost to
                    // interleaving or to its cap; WSL and SSH runs stay stderr-only.
                    val logFiles = if (state.runCommand.toolkit.host.type == ToolkitHostType.LOCAL) {
                        readSanitizerLogFiles(environment.project, sanitizer)
                    } else {
                        ""
                    }
                    publishAnalysisReport(
                        environment.project,
                        state.configuration,
                        state.runCommand,
                        sanitizer,
                        findings.stderr(),
                        logFiles,
                        console,
                    )
                }
            },
        )
        return try {
            withContext(Dispatchers.EDT) {
                ensureProjectIsOpen(environment.project)
                showRunContent(
                    state.createExecutionResult(environment.executor, processHandler),
                    environment,
                ) ?: throw ExecutionException("Failed to create the XMake run descriptor")
            }
        } catch (error: Throwable) {
            processHandler.stopAfterFailedStart(error)
            throw error
        }
    }

    /** The test run: the console prints xmake test's own results, paths hyperlinked. */
    private suspend fun executeTestRun(
        state: XMakeTestState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        val processHandler = state.startProcess()
        return try {
            withContext(Dispatchers.EDT) {
                ensureProjectIsOpen(environment.project)
                showRunContent(
                    state.createExecutionResult(environment.executor, processHandler),
                    environment,
                ) ?: throw ExecutionException("Failed to create the XMake test descriptor")
            }
        } catch (error: Throwable) {
            processHandler.stopAfterFailedStart(error)
            throw error
        }
    }

    /** The pack run: the console prints xmake pack's own output, every `packing <path> ..`
     *  line hyperlinked. With `--autobuild=n` the pack builds nothing itself, so the
     *  profile's configure pass runs first - the same two-step the launch state pairs -
     *  keeping the pack on the profile's mode/platform/toolchain. */
    private suspend fun executePackRun(
        executor: XMakeExecutor,
        state: XMakePackState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        if (!state.configuration.autoBuild) {
            executor.execute(
                console, state.configureCommand,
                XMakeConsoleOptions(showConsole = false, showOnFailure = true),
            )
        }
        val processHandler = state.startProcess()
        return try {
            withContext(Dispatchers.EDT) {
                ensureProjectIsOpen(environment.project)
                showRunContent(
                    state.createExecutionResult(environment.executor, processHandler),
                    environment,
                ) ?: throw ExecutionException("Failed to create the XMake pack descriptor")
            }
        } catch (error: Throwable) {
            processHandler.stopAfterFailedStart(error)
            throw error
        }
    }

    private suspend fun executeDebug(
        executor: XMakeExecutor,
        state: XMakeDebugState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor {
        val debugSupport = XMakeDebugSupport.find()
            ?: throw ExecutionException("XMake debugging requires the CLion debugger integration")
        val launch = try {
            prepareXMakeDebugLaunch(state, environment.project, executor)
        } catch (error: XMakeVersionRefusalException) {
            XMakeInfoManager.getInstance(environment.project)
                .updateVersionRefusal(error.refusal.toolkit, error.refusal)
            throw ExecutionException(error.refusal.message, error)
        }

        return withContext(Dispatchers.EDT) {
            ensureProjectIsOpen(environment.project)
            val starter = debugSupport.createProcessStarter(launch, environment)
            val session = XDebuggerManager.getInstance(environment.project)
                .newSessionBuilder(starter)
                .environment(environment)
                .startSession()
            session.runContentDescriptor ?: run {
                val error = ExecutionException("Failed to create the XMake debug descriptor")
                suppressCleanupError(error) { session.session.stop() }
                throw error
            }
        }
    }

    private suspend fun prepareConsole(project: Project): XMakeConsole {
        val console = XMakeConsoleManager.getInstance(project).awaitReady()
        withContext(Dispatchers.EDT) {
            ensureProjectIsOpen(project)
            FileDocumentManager.getInstance().saveAllDocuments()
            console.clear()
        }
        return console
    }
}

/** Guards feature executions: the runner must not start work on a disposed project. */
fun ensureProjectIsOpen(project: Project) {
    if (project.isDisposed) {
        throw ExecutionException("Project was disposed before XMake could start")
    }
}

private inline fun suppressCleanupError(primary: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: Throwable) {
        primary.addSuppressed(error)
    }
}

fun ProcessHandler.stopAfterFailedStart(failure: Throwable) {
    if (!isStartNotified) {
        suppressCleanupError(failure) { startNotify() }
    }
    if (!isProcessTerminated && !isProcessTerminating) {
        suppressCleanupError(failure) { destroyProcess() }
    }
}
