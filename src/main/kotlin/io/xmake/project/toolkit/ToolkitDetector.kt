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
package io.xmake.project.toolkit

import com.intellij.execution.processTools.getBareExecutionResult
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.xmake.project.toolkit.ToolkitHostType.LOCAL
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.project.toolkit.ToolkitHostType.WSL
import io.xmake.utils.Logger
import io.xmake.utils.execute.createWslProcess
import io.xmake.utils.execute.probeXmakeLocCommand
import io.xmake.utils.execute.probeXmakeLocCommandOnWin
import io.xmake.utils.execute.probeXmakeVersionCommand
import io.xmake.utils.execute.runProcess
import io.xmake.utils.extension.ToolkitHostExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runInterruptible

internal class ToolkitDetector(
    private val hostExtensions: ExtensionPointName<ToolkitHostExtension>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun detect(project: Project?): Flow<Toolkit> {
        val paths = toolkitHosts(project).logFailures().flatMapMerge { host ->
            detectToolkitLocations(host)
                .flowOn(Dispatchers.IO)
                .buffer()
                .distinctUntilChanged()
                .filterNot(String::isBlank)
                .onEach { path -> Logger.i(TAG, "output path: $path") }
                .map { path -> host to path }
                .logFailures()
        }.flowOn(Dispatchers.Default).buffer()

        return paths.flatMapMerge { (host, path) ->
            Logger.i(TAG, "detecting version: host: $host, path: $path")
            detectToolkitVersion(host, path)
                .flowOn(Dispatchers.IO)
                .buffer()
                .map { version -> createToolkit(host, path, version) }
                .logFailures()
        }.flowOn(Dispatchers.Default).buffer()
    }

    private fun toolkitHosts(project: Project?): Flow<ToolkitHost> = flow {
        emit(ToolkitHost(LOCAL).also { host -> Logger.i(TAG, "emit host: $host") })
        getInstalledWslDistributions().forEach { distribution ->
            emit(ToolkitHost(WSL, distribution).also { host -> Logger.i(TAG, "emit host: $host") })
        }
        sshHostExtensions().forEach { extension ->
            extension.getToolkitHosts(project).forEach { host ->
                emit(host)
                Logger.i(TAG, "emit host: $host")
            }
        }
    }

    private fun getInstalledWslDistributions(): List<WSLDistribution> {
        if (!WSLUtil.isSystemCompatible()) return emptyList()

        return try {
            WslDistributionManager.getInstance().installedDistributions
        } catch (error: ProcessCanceledException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, error.message ?: "Failed to read WSL distributions")
            emptyList()
        }
    }

    private fun detectToolkitLocations(host: ToolkitHost): Flow<String> = flow {
        val command = if (host.type == LOCAL && IS_WINDOWS_HOST) {
            probeXmakeLocCommandOnWin
        } else {
            probeXmakeLocCommand
        }
        val process = when (host.type) {
            LOCAL -> runInterruptible(Dispatchers.IO) {
                ProcessBuilder(command.getCommandLineList(command.exePath)).start()
            }
            WSL -> command.createWslProcess(host.target as WSLDistribution)
            SSH -> sshHostExtension().run { command.createProcess(host) }
        }

        val result = process.getBareExecutionResult()
        val output = result.stdOut.toString(Charsets.UTF_8)
        Logger.i(TAG, "Host: ${host.type} ExitCode: ${result.exitCode} Output: $output")
        if (result.exitCode != 0) return@flow

        output
            .lineSequence()
            .filterNot { line -> line.isBlank() || line.contains("not found") }
            .distinct()
            .forEach { path ->
                emit(path)
                Logger.i(TAG, "emit path on ${host.type}: $path")
            }
    }

    private fun detectToolkitVersion(host: ToolkitHost, path: String): Flow<String> = flow {
        val command = probeXmakeVersionCommand.withExePath(path)
        val process = when (host.type) {
            LOCAL -> runInterruptible(Dispatchers.IO) {
                ProcessBuilder(command.getCommandLineList(command.exePath)).start()
            }
            WSL -> command.createWslProcess(host.target as WSLDistribution)
            SSH -> sshHostExtension().run { command.createProcess(host) }
        }
        val (stdout, exitCode) = runProcess(process)
        val version = stdout.getOrNull()
            ?.let(XMAKE_VERSION_PATTERN::find)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        Logger.i(TAG, "ExitCode: $exitCode Version: $version")
        if (exitCode == 0 && version.isNotBlank()) emit(version)
    }

    private fun <T> Flow<T>.logFailures(): Flow<T> = catch { error ->
        if (error is CancellationException) throw error
        Logger.w(TAG, error.message ?: "Unknown error")
    }

    private fun createToolkit(host: ToolkitHost, path: String, version: String): Toolkit =
        when (host.type) {
            LOCAL -> Toolkit(LOCAL_HOST_NAME, host, path, version)
            WSL -> Toolkit((host.target as WSLDistribution).presentableName, host, path, version)
            SSH -> sshHostExtension().createToolkit(host, path, version)
        }.apply {
            isRegistered = false
            isValid = true
        }

    private fun sshHostExtensions(): List<ToolkitHostExtension> =
        hostExtensions.extensionList.filter { extension -> extension.KEY == "SSH" }

    private fun sshHostExtension(): ToolkitHostExtension =
        sshHostExtensions().firstOrNull() ?: error("SSH toolkit host extension is unavailable")

    companion object {
        private const val TAG = "ToolkitDetector"
        private val LOCAL_HOST_NAME = System.getProperty("os.name").orEmpty().ifBlank { "Local" }
        private val IS_WINDOWS_HOST = LOCAL_HOST_NAME.startsWith("Windows", ignoreCase = true)
        private val XMAKE_VERSION_PATTERN = Regex("""\bxmake\s+(v[^,\s]+)""", RegexOption.IGNORE_CASE)
    }
}
