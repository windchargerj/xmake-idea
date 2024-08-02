package io.xmake.utils.execute

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.processTools.getResultStdoutStr
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ssh.*
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.interaction.PlatformSshPasswordProvider
import com.intellij.util.io.awaitExit
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType.*
import io.xmake.project.xmakeConsoleView
import io.xmake.project.xmakeProblemList
import io.xmake.shared.XMakeProblem
import io.xmake.utils.SystemUtils.parseProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.charset.Charset

private val Log = fileLogger()

fun GeneralCommandLine.createLocalProcess(): Process{
    return this
        .also { Log.info("commandOnLocal: $this") }
        .toProcessBuilder().start()
}

fun GeneralCommandLine.createWslProcess(wslDistribution: WSLDistribution, project: Project? = null): Process {
    val commandInWsl: GeneralCommandLine = wslDistribution.patchCommandLine(
        object: GeneralCommandLine(this){}, project, WSLCommandLineOptions().apply {
            isLaunchWithWslExe = true
        }).apply{
            workDirectory?.let {
                withWorkDirectory(WslPath(wslDistribution.id, workDirectory.path).toWindowsUncPath())
            }
            withCharset(charset)
        }
    return commandInWsl
        .also { Log.info("commandInWsl: $commandInWsl") }
        .toProcessBuilder().start()
}

fun GeneralCommandLine.createSshProcess(sshConfig: SshConfig): Process {
    val builder = ConnectionBuilder(sshConfig.host)
        .withSshPasswordProvider(PlatformSshPasswordProvider(sshConfig.copyToCredentials()))

    return builder
        .also { Log.info("commandOnRemote: $this") }
        .processBuilder(this).start()
}

fun GeneralCommandLine.createProcess(toolkit: Toolkit): Process {
    return with(toolkit) {
        Log.info("createProcessWithToolkit: $toolkit")
        when (host.type) {
            LOCAL -> {
                this@createProcess.createLocalProcess()
            }

            WSL -> {
                val wslDistribution = host.target as WSLDistribution
                this@createProcess.createWslProcess(wslDistribution)
            }

            SSH -> {
                val sshConfig = host.target as SshConfig
                this@createProcess.createSshProcess(sshConfig)
            }
        }
    }
}

suspend fun runProcess(process: Process): Pair<Result<String>, Int>{
    val result = process.getResultStdoutStr()
    val exitCode = process.awaitExit()
    return Pair(result, exitCode)
}