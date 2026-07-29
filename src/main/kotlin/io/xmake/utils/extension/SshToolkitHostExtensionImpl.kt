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
 * @file        SshToolkitHostExtensionImpl.kt
 *
 */
package io.xmake.utils.extension

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.ssh.*
import com.intellij.ssh.channels.SftpChannel
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.ssh.interaction.PlatformSshPasswordProvider
import com.intellij.ssh.ui.sftpBrowser.RemoteBrowserDialog
import com.intellij.ssh.ui.sftpBrowser.SftpRemoteBrowserProvider
import io.xmake.project.directory.ui.DirectoryBrowser
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHost
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.utils.execute.SyncDirection
import io.xmake.utils.execute.rmRecur
import kotlinx.coroutines.*
import java.awt.event.ActionListener
import java.io.File
import kotlin.io.path.Path

class SshToolkitHostExtensionImpl : ToolkitHostExtension {

    override val KEY: String = "SSH"

    override fun getToolkitHosts(project: Project?): List<ToolkitHost> {
        return sshConfigManager(project).configs.map {
            ToolkitHost(ToolkitHostType.SSH, it)
        }
    }

    override fun createToolkit(host: ToolkitHost, path: String, version: String): Toolkit {
        val sshConfig = host.requireSshConfig()
        val name = sshConfig.presentableShortName
        return Toolkit(name, host, path, version)
    }

    override suspend fun syncProject(
        project: Project,
        host: ToolkitHost,
        direction: SyncDirection,
        remoteDirectory: String,
    ) {
        val sshConfig = host.requireSshConfig()
        val projectDirectory = project.guessProjectDir()?.path
            ?: project.basePath
            ?: throw IllegalStateException("Cannot resolve project directory")
        val projectDirectoryFile = File(projectDirectory)
        val builder = connectionBuilder(sshConfig)
        val cancellationContext = currentCoroutineContext()
        val sftpChannel = runInterruptible(Dispatchers.IO) {
            builder.openFailSafeSftpChannel()
        }

        try {
            runInterruptible(Dispatchers.IO) {
                cancellationContext.ensureActive()
                when (direction) {
                    SyncDirection.LOCAL_TO_UPSTREAM -> {
                        sftpChannel.requireSafeSyncDestination(remoteDirectory)
                        try {
                            sftpChannel.rmRecur(remoteDirectory)
                        } catch (error: SftpChannelNoSuchFileException) {
                            Log.debug("Remote sync directory does not exist yet: $remoteDirectory", error)
                        }

                        sftpChannel.uploadFileOrDir(
                            projectDirectoryFile,
                            remoteDir = remoteDirectory,
                            relativePath = "/",
                            progressTracker = object : SftpProgressTracker {
                                override val isCanceled: Boolean
                                    get() = !cancellationContext.isActive

                                override fun onBytesTransferred(count: Long) {}

                                override fun onFileCopied(file: File) {}
                            },
                            filesFilter = { file ->
                                listOf(".xmake", ".idea", "build", ".gitignore")
                                    .all {
                                        !file.startsWith(
                                            Path(projectDirectory, it).toFile()
                                        )
                                    }
                            },
                            persistExecutableBit = true,
                        )
                    }

                    SyncDirection.UPSTREAM_TO_LOCAL -> {
                        sftpChannel.downloadFileOrDir(remoteDirectory, projectDirectory)
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                sftpChannel.close()
            }
        }
    }

    override suspend fun ToolkitHost.loadHostTarget(project: Project?) {
        target = id?.let(sshConfigManager(project)::findConfigById)
    }

    override fun getTargetId(target: Any?): String {
        val sshConfig = target as? SshConfig
            ?: throw IllegalArgumentException("SSH toolkit target must be an SshConfig")
        return sshConfig.id
    }

    override fun DirectoryBrowser.createBrowseListener(host: ToolkitHost): ActionListener {
        val sshConfig = host.requireSshConfig()

        return ActionListener {
            try {
                val selectedDirectory = runBlocking(Dispatchers.IO) {
                    connectionBuilder(sshConfig).openFailSafeSftpChannel()
                }.use { channel ->
                    val dialog = RemoteBrowserDialog(
                        remoteBrowserProvider = SftpRemoteBrowserProvider(channel),
                        project = project,
                        foldersOnly = true,
                        hostName = sshConfig.presentableShortName,
                        pathToExpand = text.takeIf(String::isNotBlank),
                        withCreateDirectoryButton = true,
                    )
                    if (dialog.showAndGet()) dialog.getResult() else null
                }
                selectedDirectory?.let { text = it }
            } catch (error: Exception) {
                Log.warn("Failed to open the SSH directory browser", error)
                Messages.showErrorDialog(
                    project,
                    error.message ?: "Unable to open the SSH directory browser",
                    "SSH Directory Browser",
                )
            }
        }
    }

    override fun GeneralCommandLine.createProcess(host: ToolkitHost): Process {
        val sshConfig = host.requireSshConfig()
        Log.info("commandOnRemote: $commandLineString")
        return connectionBuilder(sshConfig)
            .processBuilder(this)
            .withAllocatePty(false)
            .start()
    }

    private fun connectionBuilder(sshConfig: SshConfig): ConnectionBuilder =
        ConnectionBuilder(sshConfig.host)
            .withSshPasswordProvider(PlatformSshPasswordProvider(sshConfig.copyToCredentials()))

    private fun sshConfigManager(project: Project?): SshConfigManager =
        SshConfigManager.getInstance(project)

    private fun ToolkitHost.requireSshConfig(): SshConfig =
        target as? SshConfig
            ?: error("SSH toolkit host is unavailable: ${id.orEmpty()}")

    private fun SftpChannel.requireSafeSyncDestination(path: String) {
        val destination = runCatching { canonicalize(path) }
            .getOrDefault(path)
            .trimEnd('/')
        val homeDirectory = canonicalize(home).trimEnd('/')
        require(destination.isNotEmpty() && destination != homeDirectory) {
            "Refusing to replace unsafe SSH sync directory: $path"
        }
    }

    companion object {
        private val Log = logger<SshToolkitHostExtensionImpl>()
    }
}
