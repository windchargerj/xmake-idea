package io.xmake.utils.execute

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.processTools.getResultStdoutStr
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.execution.wsl.rootMappings
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.io.awaitExit
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType.*
import io.xmake.project.xmakeConsoleView
import io.xmake.project.xmakeOutputPanel
import io.xmake.project.xmakeProblemList
import io.xmake.project.xmakeToolWindow
import io.xmake.shared.XMakeProblem
import io.xmake.utils.SystemUtils.parseProblem
import io.xmake.utils.extension.ToolkitHostExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private val Log = fileLogger()

private val EP_NAME: ExtensionPointName<ToolkitHostExtension> =
    ExtensionPointName("io.xmake.toolkitHostExtension")

fun GeneralCommandLine.createLocalProcess(): Process{
    return this
        .also { Log.info("commandOnLocal: ${this.commandLineString}") }
        .toProcessBuilder().start()
}

fun GeneralCommandLine.createWslProcess(wslDistribution: WSLDistribution, project: Project? = null): Process {
    val commandInWsl: GeneralCommandLine = wslDistribution.patchCommandLine(
        object : GeneralCommandLine(this) {
            init {
                parametersList.clearAll()
            }
        }, project,
        WSLCommandLineOptions().apply {
            wslDistribution.rootMappings
            isLaunchWithWslExe = true
//            remoteWorkingDirectory = workingDirectory?.toCanonicalPath()
        }
    ).apply {
        workDirectory?.let {
            withWorkDirectory(WslPath(wslDistribution.id, it.path).toWindowsUncPath())
        }
        parametersList.replaceOrAppend(this@createWslProcess.exePath, this@createWslProcess.commandLineString)
    }
    return commandInWsl
        .also { Log.info("commandInWsl: ${commandInWsl.commandLineString}") }
        .toProcessBuilder().start()
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
                with(EP_NAME.extensions.first { it.KEY == "SSH" }) {
                    createProcess(toolkit.host)
                }
            }
        }
    }
}

suspend fun runProcess(process: Process): Pair<Result<String>, Int>{
    val result = process.getResultStdoutStr()
    val exitCode = process.awaitExit()
    return Pair(result, exitCode)
}

fun runProcessWithHandler(
    project: Project,
    command: GeneralCommandLine,
    showConsole: Boolean = true,
    showProblem: Boolean = false,
    showExitCode: Boolean = false,
    createProcess: (GeneralCommandLine) -> Process,
): ProcessHandler? {

    val process = try {
        createProcess(command)
    } catch (e: ProcessNotCreatedException) {
        return null
    }
    val processHandler = KillableColoredProcessHandler(process, command.commandLineString, Charsets.UTF_8)
    var content = ""

    processHandler.addProcessListener(object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            super.onTextAvailable(event, outputType)
            project.xmakeConsoleView.print(event.text, ConsoleViewContentType.getConsoleViewType(outputType))
            content += event.text
        }
    })

    if (showConsole) {
        project.xmakeToolWindow?.show {
            project.xmakeOutputPanel.showPanel()
        }
    }

    if (showProblem) {
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(e: ProcessEvent) {
                runBlocking(Dispatchers.Default) {
                    val problems = mutableListOf<XMakeProblem>()
                    println("Content: $content")
                    content.split(Regex("\\r\\n|\\n|\\r")).forEach {
                        val problem = parseProblem(it.trim())
                        if (problem !== null) {
                            problems.add(problem)
                        }
                    }
                    project.xmakeProblemList = problems
                }
            }
        })
    }

    if (showExitCode) {
        ProcessTerminatedListener.attach(processHandler)
    }

    processHandler.startNotify()
    ProcessTerminatedListener.attach(processHandler, project)
    return processHandler
}