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

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.processTools.getBareExecutionResult
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.xmake.project.toolkit.ToolkitHostScan.Completed
import io.xmake.project.toolkit.ToolkitHostScan.Failed
import io.xmake.project.toolkit.ToolkitHostType.LOCAL
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.project.toolkit.ToolkitHostType.WSL
import io.xmake.utils.Logger
import io.xmake.utils.execute.createWslProcess
import io.xmake.utils.execute.probeXmakeLocCommand
import io.xmake.utils.execute.probeXmakeLocCommandOnWin
import io.xmake.utils.execute.probeXmakeVersionCommand
import io.xmake.utils.extension.ToolkitHostExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible

internal class ToolkitDetector(
    private val hostExtensions: ExtensionPointName<ToolkitHostExtension>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun detect(project: Project?): Flow<ToolkitHostScan> = toolkitHosts(project)
        .flatMapMerge { host ->
            flow { emit(scanHost(project, host)) }
        }
        .flowOn(Dispatchers.IO)
        .buffer()

    private suspend fun scanHost(project: Project?, host: ToolkitHost): ToolkitHostScan = try {
        Completed(host, detectToolkits(project, host))
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Failed(host, error)
    }

    private suspend fun detectToolkits(project: Project?, host: ToolkitHost): List<Toolkit> {
        return detectToolkitLocations(project, host).map { path ->
            Logger.i(TAG, "detecting version: host: $host, path: $path")
            createToolkit(host, path, detectToolkitVersion(project, host, path))
        }
    }

    private fun toolkitHosts(project: Project?): Flow<ToolkitHost> = flow {
        emit(ToolkitHost(LOCAL).also { host -> Logger.i(TAG, "emit host: $host") })
        getInstalledWslDistributions().forEach { distribution ->
            emit(ToolkitHost(WSL, distribution).also { host -> Logger.i(TAG, "emit host: $host") })
        }
        sshHostExtensions().forEach { extension ->
            val hosts = try {
                extension.getToolkitHosts(project)
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, error.message ?: "Failed to read SSH hosts")
                emptyList()
            }
            hosts.forEach { host ->
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

    private suspend fun detectToolkitLocations(project: Project?, host: ToolkitHost): List<String> {
        val command = if (host.type == LOCAL && IS_WINDOWS_HOST) {
            probeXmakeLocCommandOnWin
        } else {
            probeXmakeLocCommand
        }
        val result = execute(project, host, command)
        val output = result.stdOut.toString(Charsets.UTF_8)
        val paths = output
            .lineSequence()
            .filterNot { line -> line.isBlank() || line.contains("not found") }
            .distinct()
            .toList()
        Logger.i(TAG, "Host: ${host.type} ExitCode: ${result.exitCode} Output: $output")
        paths.forEach { path -> Logger.i(TAG, "detected path on ${host.type}: $path") }

        if (result.exitCode != 0 && paths.isEmpty()) {
            throw ExecutionException(commandFailure("XMake location probe", result.exitCode, result.stdErr))
        }
        return paths
    }

    private suspend fun detectToolkitVersion(project: Project?, host: ToolkitHost, path: String): String {
        val command = probeXmakeVersionCommand.withExePath(path)
        val result = execute(project, host, command)
        val output = result.stdOut.toString(Charsets.UTF_8)
        val version = XMAKE_VERSION_PATTERN.find(output)?.groupValues?.get(1).orEmpty()
        Logger.i(TAG, "Host: ${host.type} ExitCode: ${result.exitCode} Version: $version")

        if (result.exitCode != 0 || version.isBlank()) {
            throw ExecutionException(commandFailure("XMake version probe", result.exitCode, result.stdErr))
        }
        return version
    }

    private suspend fun execute(project: Project?, host: ToolkitHost, command: GeneralCommandLine) =
        createProcess(project, host, command).getBareExecutionResult()

    private suspend fun createProcess(project: Project?, host: ToolkitHost, command: GeneralCommandLine): Process =
        when (host.type) {
            LOCAL -> runInterruptible(Dispatchers.IO) {
                ProcessBuilder(command.getCommandLineList(command.exePath)).start()
            }
            WSL -> command.createWslProcess(host.target as WSLDistribution, project)
            SSH -> sshHostExtension().run { command.createProcess(host) }
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

    private fun commandFailure(description: String, exitCode: Int, stderr: ByteArray): String {
        val detail = stderr.toString(Charsets.UTF_8).trim()
        return buildString {
            append(description).append(" failed with exit code ").append(exitCode)
            if (detail.isNotEmpty()) append(": ").append(detail)
        }
    }

    companion object {
        private const val TAG = "ToolkitDetector"
        private val LOCAL_HOST_NAME = System.getProperty("os.name").orEmpty().ifBlank { "Local" }
        private val IS_WINDOWS_HOST = LOCAL_HOST_NAME.startsWith("Windows", ignoreCase = true)
        private val XMAKE_VERSION_PATTERN = Regex("""xmake\s+(v[^,\s]+)""", RegexOption.IGNORE_CASE)
    }
}
