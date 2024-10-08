package io.xmake.utils

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessNotCreatedException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import io.xmake.project.toolkit.activatedToolkit
import io.xmake.shared.XMakeProblem
import io.xmake.utils.exception.XMakeToolkitNotSetException
import io.xmake.utils.execute.createProcess
import io.xmake.utils.execute.runProcessWithHandler
import io.xmake.utils.interact.kXMakeVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

object SystemUtils {

    @Deprecated("Please refer to the relevant content in folder io/xmake/project/toolkit.")
    // the xmake program
    private var _xmakeProgram: String = ""

    @Deprecated("Please refer to the relevant content in folder io/xmake/project/toolkit.")
    var xmakeProgram: String
        get() {

            // cached? return it directly
            if (_xmakeProgram != "") {
                return _xmakeProgram
            }

            // for windows? return xmake directly
            if (SystemInfo.isWindows) {
                _xmakeProgram = "xmake"
                return _xmakeProgram
            }

            // attempt to get xmake program
            val programs = arrayOf(
                "xmake",
                (System.getenv("HOME") ?: "") + "/.local/bin/xmake",
                "/usr/local/bin/xmake",
                "/usr/bin/xmake",
                "/opt/homebrew/bin/xmake"
            )
            for (program in programs) {
                if (program == "xmake" || File(program).exists()) {
                    val result = ioRunvInPool(listOf(program, "--version"))
                    if (result.isNotEmpty()) {
                        _xmakeProgram = program
                        break
                    }
                }
            }
            return _xmakeProgram
        }
        set(value) {
            _xmakeProgram = value
        }

    @Deprecated("Please refer to the relevant content in folder io/xmake/project/toolkit.")
    // the xmake version
    private var _xmakeVersion: String = ""

    @Deprecated("Please refer to the relevant content in folder io/xmake/project/toolkit.")
    var xmakeVersion: String
        get() {
            if (_xmakeVersion == "") {
                val result = kXMakeVersion
                if (result.isNotEmpty()) {
                    _xmakeVersion = result
                }
            }
            return _xmakeVersion
        }
        set(value) {
            _xmakeVersion = value
        }

    // get platform
    fun platform(): String = when {
        SystemInfo.isWindows -> "windows"
        SystemInfo.isMac -> "macosx"
        else -> "linux"
    }

    // parse problems for the given line
    fun parseProblem(info: String): XMakeProblem? {

        if (SystemInfo.isWindows) {

            // gbk => utf8
            val info_utf8 = String(info.toByteArray(), charset("UTF-8"))

            // parse problem info
            val pattern = Pattern.compile("(.*?)\\(([0-9]*)\\): (.*?) .*?: (.*)")
            val matcher = pattern.matcher(info_utf8)
            if (matcher.find()) {
                val file = matcher.group(1)
                val line = matcher.group(2)
                val kind = matcher.group(3)
                val message = matcher.group(4)
                return XMakeProblem(file, line, "0", kind, message)
            }

        } else {

            // parse problem info
            val pattern = Pattern.compile("^(error: )?(.*?):([0-9]*):([0-9]*): (.*?): (.*)\$")
            val matcher = pattern.matcher(info)
            if (matcher.find()) {
                val file = matcher.group(2)
                val line = matcher.group(3)
                val column = matcher.group(4)
                val kind = matcher.group(5)
                val message = matcher.group(6)
                return XMakeProblem(file, line, column, kind, message)
            }
        }
        return null
    }

    fun runvInConsole(
        project: Project,
        commandLine: GeneralCommandLine,
        showConsole: Boolean = true,
        showProblem: Boolean = false,
        showExitCode: Boolean = false
    ) = runProcessWithHandler(project, commandLine, showConsole, showProblem, showExitCode) {
        println("runvInConsole: ${it.workDirectory}")
        try {
            val activatedToolkit = project.activatedToolkit ?: throw XMakeToolkitNotSetException()
            runBlocking(Dispatchers.Default) {
                commandLine.createProcess(activatedToolkit)
            }
        } catch (e: XMakeToolkitNotSetException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("XMake")
                .createNotification("Error with XMake Toolkit", e.message ?: "", NotificationType.ERROR)
                .notify(project)
            throw ProcessNotCreatedException(e.message ?: "", commandLine)
        }
    }

}

val VirtualFile.pathAsPath: Path get() = Paths.get(path)
