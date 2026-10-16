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

import com.intellij.execution.ExecutionException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.profiler.ProfilerDumpParserProviderFinder
import com.intellij.profiler.api.ProfilerDumpParserProvider
import com.intellij.profiler.api.ProfilerDumpDescriptor
import com.intellij.profiler.api.getProfilerDumpParserProviders
import io.xmake.project.console.XMakeConsole
import io.xmake.run.XMakeRunner
import io.xmake.run.ensureProjectIsOpen
import io.xmake.run.stopAfterFailedStart
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutionService
import io.xmake.utils.execute.createProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prepares a profile run: builds with the profile mode's symbols, then records the target
 * with Linux `perf` through `xmake l runtool.lua`. The data file is announced on exit for
 * `perf report`.
 */
private val Log = Logger.getInstance("io.xmake.run.perf")

internal suspend fun executePerf(
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

    val processHandler = state.startProcess()
    return try {
        val content = withContext(Dispatchers.EDT) {
            ensureProjectIsOpen(environment.project)
            val descriptor = showRunContent(
                state.createExecutionResult(environment.executor, processHandler),
                environment,
            ) ?: throw ExecutionException("Failed to create the XMake run descriptor")
            // perf records until the program exits: an interactive target keeps waiting
            // for its input right here, and the flame graph opens the moment it is done.
            state.runConsole?.print(
                "perf is recording; use the program in this console. " +
                    "The flame graph opens when it exits.\n",
                com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT,
            )
            descriptor
        }
        processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                // The folding normally happens where the recording happened, inside the
                // tool run; whatever interrupted it, the recording itself can still be
                // folded here, so the flame graph does not depend on that one step.
                val collapsed = foldPerfRecording(environment.project, state) ?: state.collapsedFile
                val opened = openPerfSnapshot(environment.project, collapsed)
                notifyPerfProfile(environment.project, state.dataFile, opened)
            }
        })
        content
    } catch (error: Throwable) {
        processHandler.stopAfterFailedStart(error)
        throw error
    }
}

/**
 * Folds the run's recording into the collapsed-stacks format when the tool run did not
 * get to do it: `perf script` reads the recording wherever the toolkit ran it, and the
 * samples fold the same way the tool-side folding does. Returns null when the folded
 * file is already there or cannot be produced.
 */
private fun foldPerfRecording(project: Project, state: XMakePerfState): File? {
    val collapsed = state.collapsedFile
    if (collapsed.isFile || !state.dataFile.isFile) return null
    Log.info("No folded stacks for ${state.dataFile.name}; folding the recording on the IDE side")
    val toolkit = state.toolCommand.toolkit
    val distribution = io.xmake.project.toolkit.ToolkitHostType.WSL
        .takeIf { toolkit.host.type == it }?.let { toolkit.host.wslDistribution }
    val recordingPath = distribution?.getWslPath(state.dataFile.toPath()) ?: state.dataFile.absolutePath
    return runCatching {
        val command = GeneralCommandLine("perf", "script", "-i", recordingPath)
        // The WSL command patching requires a progress indicator on the thread; the
        // process listener has none, so an empty indicator stands in for it.
        val process = ProgressManager.getInstance().runProcess(
            Computable { command.createProcess(toolkit, project, null) },
            EmptyProgressIndicator(),
        )
        val script = process.inputStream.bufferedReader().readText()
        val finished = runCatching { process.waitFor(60, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished || process.exitValue() != 0) {
            Log.info("perf script failed on $recordingPath: $script")
            return null
        }
        val counts = foldScriptSamples(script)
        if (counts.isEmpty()) return null
        collapsed.writeText(
            counts.entries.joinToString(separator = "\n") { (stack, count) -> "$stack $count" } + "\n",
        )
        Log.info("Folded ${counts.size} distinct stack(s) from ${state.dataFile.name} on the IDE side")
        collapsed
    }.getOrElse { error ->
        Log.info("Folding ${state.dataFile.name} on the IDE side failed: ${error.message}")
        null
    }
}

/** Folds `perf script` output: header lines start a sample, indented lines are frames with
 *  the leaf first; identical samples are counted together. */
private fun foldScriptSamples(script: String): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    val stack = ArrayDeque<String>()
    for (rawLine in script.lineSequence()) {
        val line = rawLine.trimEnd('\r')
        if (line.startsWith(' ') || line.startsWith('\t')) {
            val trimmed = line.trim()
            val symbol = trimmed.split(' ', '\t').getOrNull(1)
                ?: trimmed.split(' ', '\t').firstOrNull()
            if (!symbol.isNullOrBlank()) stack.addFirst(symbol)
        } else {
            if (stack.isNotEmpty()) {
                val key = stack.joinToString(";")
                counts[key] = (counts[key] ?: 0) + 1
                stack.clear()
            }
        }
    }
    if (stack.isNotEmpty()) {
        val key = stack.joinToString(";")
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts
}

/**
 * Hands the folded stacks to the IDE profiler view. The collapsed-stacks parser is CLion's
 * own; IDEA Ultimate registers only its Java-format parsers, so there the call degrades to
 * the notification that names the perf report command, which is the right outcome for a
 * C/C++ profiling tool outside the C/C++ IDE.: the platform finds the registered dump
 * parser, imports the snapshot and shows the flame graph — the same view the native
 * profilers open. The profiler module is IDE-dependent, so a missing module only skips the
 * view and leaves the files on disk.
 */
private fun openPerfSnapshot(project: Project, collapsedFile: File): Boolean =
    runCatching {
        if (!collapsedFile.isFile) return false
        val providers = getProfilerDumpParserProviders()
        val provider = findProfilerParser(collapsedFile, providers) ?: return false
        invokeLater {
            if (project.isDisposed) return@invokeLater
            ProfilerDumpDescriptor(
                project,
                "XMake perf",
                Date(),
                collapsedFile,
                provider,
                AtomicInteger(),
            ).open(null)
        }
        true
    }.getOrElse { error ->
        Log
            .info("The profiler view is unavailable: ${error.message}")
        false
    }

/**
 * Asks the platform's parser finder for the provider of a recording. The finder took the
 * recording as a File up to 2026.1 and as a Path from 2026.2; a compiled reference to either
 * overload would fail on the other release, so both are looked up by name and the one the
 * running IDE has answers.
 */
private fun findProfilerParser(
    file: File,
    providers: List<ProfilerDumpParserProvider>,
): ProfilerDumpParserProvider? {
    val finder = ProfilerDumpParserProviderFinder()
    val result = invokeFinder(finder, File::class.java, file, providers)
        ?: invokeFinder(finder, Path::class.java, file.toPath(), providers)
        ?: return null
    return (result as? ProfilerDumpParserProviderFinder.Result.Success)?.provider
}

private fun invokeFinder(
    finder: Any,
    argumentType: Class<*>,
    argument: Any,
    providers: List<ProfilerDumpParserProvider>,
): Any? = runCatching {
    finder.javaClass
        .getMethod("findParserProvider", argumentType, List::class.java)
        .invoke(finder, argument, providers)
}.getOrNull()

/** Points the user at the profile data; the flame graph view opens alongside it. */
private fun notifyPerfProfile(project: Project, dataFile: File, openedInView: Boolean) {
    val detail = when {
        openedInView ->
            "Flame graph opened in the Profiler view. " +
                "Raw recording: ${dataFile.absolutePath}"
        dataFile.isFile ->
            "Profile data: ${dataFile.absolutePath}\n" +
                "Inspect it with: perf report -i \"${dataFile.absolutePath}\""
        else ->
            "perf produced no data. Check that perf is installed and permitted " +
                "(see /proc/sys/kernel/perf_event_paranoid)."
    }
    NotificationGroupManager.getInstance()
        .getNotificationGroup("XMake.NotificationGroup")
        .createNotification("XMake profile run finished", detail, NotificationType.INFORMATION)
        .notify(project)
}
