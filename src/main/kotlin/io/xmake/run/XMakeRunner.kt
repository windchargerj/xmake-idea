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
import io.xmake.project.console.XMakeConsole
import io.xmake.project.console.xmakeConsoleService
import io.xmake.run.command.XMakeExecutionService
import io.xmake.run.command.xmakeExecutionService
import io.xmake.run.state.XMakeDebugState
import io.xmake.run.state.XMakePerfState
import io.xmake.run.state.XMakeRunState
import io.xmake.run.coverage.prepareCoverageRun
import io.xmake.run.coverage.reportCoverageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.toPromiseWithoutLogError
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import io.xmake.debug.resolveXMakeTargetLocation
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.state.XMakeValgrindState

class XMakeRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        profile is XMakeRunConfiguration && when (executorId) {
            DefaultRunExecutor.EXECUTOR_ID -> true
            DefaultDebugExecutor.EXECUTOR_ID -> XMakeDebugSupport.isAvailable()
            CoverageExecutor.EXECUTOR_ID -> true
            XMakeRunConfiguration.VALGRIND_EXECUTOR_ID -> true
            XMakeRunConfiguration.PERF_EXECUTOR_ID -> true
            else -> false
        }

    override fun getRunnerId(): String = "XMakeRunner"

    override fun execute(
        environment: ExecutionEnvironment,
        state: RunProfileState,
    ): Promise<RunContentDescriptor?> {
        val project = environment.project
        val execution = project.xmakeExecutionService
        val deferred = when (state) {
            is XMakeRunState -> execution.submit {
                val console = prepareConsole(project)
                executeRun(execution, state, environment, console)
            }
            // The build runs through ProjectTaskManager *before* the mutex is acquired: it takes
            // the mutex itself internally, and the mutex is not reentrant (see
            // XMakeExecutionService.submitAfter).
            is XMakeDebugState -> execution.submitAfter(
                before = { prepareXMakeDebugBuild(project, state) },
                task = { executeDebug(execution, state, environment) },
            )
            is XMakeValgrindState -> execution.submit {
                val console = prepareConsole(project)
                executeValgrind(execution, state, environment, console)
            }
            is XMakePerfState -> execution.submit {
                val console = prepareConsole(project)
                executePerf(execution, state, environment, console)
            }
            else -> execution.submit {
                throw ExecutionException("Unsupported XMake run profile state: ${state::class.java.name}")
            }
        }
        return deferred.toPromiseWithoutLogError()
    }

    private suspend fun executeRun(
        execution: XMakeExecutionService,
        state: XMakeRunState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        // Coverage reconfigures with -m coverage and rebuilds before the normal run starts, then
        // collects the gcov data once the target exits.
        val isCoverage = environment.executor.id == CoverageExecutor.EXECUTOR_ID
        if (isCoverage) {
            prepareCoverageRun(state.configuration, environment, execution, console)
        } else {
            execution.execute(console, state.configureCommand)
        }

        val processHandler = state.startProcess {
            if (isCoverage) {
                reportCoverageData(environment.project, console)
            }
        }
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

    /**
     * Prepares a Valgrind run: configures the profile in xmake's valgrind mode, builds, resolves
     * the produced binary, then starts it under Valgrind. The report is announced on exit; the
     * IDE's memory view picks it up through its import action.
     */
    private suspend fun executeValgrind(
        execution: XMakeExecutionService,
        state: XMakeValgrindState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        execution.execute(console, state.configureCommand)
        execution.execute(
            console,
            state.buildCommand,
            XMakeConsoleOptions(showConsole = false, showProblems = true),
        )
        val output = execution.captureStandardOutput(state.targetPathCommand)
        state.resolvedTarget = resolveXMakeTargetLocation(
            state.targetName,
            output,
            state.targetPathCommand.workingDirectory,
        )

        val processHandler = state.startProcess()
        return try {
            val content = withContext(Dispatchers.EDT) {
                ensureProjectIsOpen(environment.project)
                showRunContent(
                    state.createExecutionResult(environment.executor, processHandler),
                    environment,
                ) ?: throw ExecutionException("Failed to create the XMake run descriptor")
            }
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    notifyValgrindReport(environment.project, state.reportFile)
                }
            })
            content
        } catch (error: Throwable) {
            processHandler.stopAfterFailedStart(error)
            throw error
        }
    }

    /** Points the user at the report; the memory view reads it through its import action. */
    private fun notifyValgrindReport(project: Project, reportFile: java.io.File) {
        val detail = if (reportFile.isFile) {
            "Valgrind report: ${reportFile.absolutePath}\n" +
                "Open it with Run | Import Valgrind Results."
        } else {
            "Valgrind produced no report. Check that valgrind is installed and on the PATH."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("XMake.NotificationGroup")
            .createNotification("XMake Valgrind run finished", detail, NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * Prepares a profile run: builds with the profile mode's symbols, then records the target
     * with Linux `perf`. The resulting data file is announced on exit for `perf report`.
     */
    private suspend fun executePerf(
        execution: XMakeExecutionService,
        state: XMakePerfState,
        environment: ExecutionEnvironment,
        console: XMakeConsole,
    ): RunContentDescriptor {
        execution.execute(console, state.configureCommand)
        execution.execute(
            console,
            state.buildCommand,
            XMakeConsoleOptions(showConsole = false, showProblems = true),
        )
        val output = execution.captureStandardOutput(state.targetPathCommand)
        state.resolvedTarget = resolveXMakeTargetLocation(
            state.targetName,
            output,
            state.targetPathCommand.workingDirectory,
        )

        val processHandler = state.startProcess()
        return try {
            val content = withContext(Dispatchers.EDT) {
                ensureProjectIsOpen(environment.project)
                showRunContent(
                    state.createExecutionResult(environment.executor, processHandler),
                    environment,
                ) ?: throw ExecutionException("Failed to create the XMake run descriptor")
            }
            processHandler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    notifyPerfProfile(environment.project, state.dataFile)
                }
            })
            content
        } catch (error: Throwable) {
            processHandler.stopAfterFailedStart(error)
            throw error
        }
    }

    /** Points the user at the profile data; `perf report` renders it from a terminal. */
    private fun notifyPerfProfile(project: Project, dataFile: java.io.File) {
        val detail = if (dataFile.isFile) {
            "Profile data: ${dataFile.absolutePath}\n" +
                "Inspect it with: perf report -i \"${dataFile.absolutePath}\""
        } else {
            "perf produced no data. Check that perf is installed and permitted " +
                "(see /proc/sys/kernel/perf_event_paranoid)."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("XMake.NotificationGroup")
            .createNotification("XMake profile run finished", detail, NotificationType.INFORMATION)
            .notify(project)
    }

    private suspend fun executeDebug(
        execution: XMakeExecutionService,
        state: XMakeDebugState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor {
        val debugSupport = XMakeDebugSupport.find()
            ?: throw ExecutionException("XMake debugging requires the CLion debugger integration")
        val launch = prepareXMakeDebugLaunch(state, environment.project, execution)

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
        val console = project.xmakeConsoleService.awaitReady()
        withContext(Dispatchers.EDT) {
            ensureProjectIsOpen(project)
            FileDocumentManager.getInstance().saveAllDocuments()
            console.clear()
        }
        return console
    }

    private fun ensureProjectIsOpen(project: Project) {
        if (project.isDisposed) {
            throw ExecutionException("Project was disposed before XMake could start")
        }
    }

    private fun ProcessHandler.stopAfterFailedStart(failure: Throwable) {
        if (!isStartNotified) {
            suppressCleanupError(failure) { startNotify() }
        }
        if (!isProcessTerminated && !isProcessTerminating) {
            suppressCleanupError(failure) { destroyProcess() }
        }
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
