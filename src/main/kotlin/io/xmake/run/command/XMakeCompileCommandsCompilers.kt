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
 * @file        XMakeCompileCommandsCompilers.kt
 *
 */

package io.xmake.run.command

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import io.xmake.project.toolkit.ToolkitHostType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val Log = Logger.getInstance("io.xmake.run.command.XMakeCompileCommandsCompilers")

/** The mode the compile-commands generator has always used. */
internal const val CLANGD_COMPILE_COMMANDS_LSP = "clangd"

/**
 * Resolves the compile-commands mode for this profile. `--lsp=clangd` embeds
 * the MSVC include directories as clang-only `-imsvc` flags, which the named
 * `cl.exe` cannot parse; a database for MSVC is therefore generated plainly
 * and CLion supplies those directories through the bound toolchain. Every
 * other compiler keeps the clangd mode it has always used.
 *
 * The query runs only for Windows-local toolkits: MSVC is the only compiler
 * whose database depends on the mode, and the script is a local resource. A
 * failed query falls back to the clangd mode instead of failing the update.
 */
@Suppress("OPT_IN_USAGE")
internal suspend fun XMakeCommandFactory.resolveCompileCommandsLsp(project: Project): String? {
    if (toolkit.host.type != ToolkitHostType.LOCAL || OS.CURRENT != OS.Windows) {
        return CLANGD_COMPILE_COMMANDS_LSP
    }
    val output = try {
        project.xmakeExecutor.captureStandardOutput(createCompilersQuery())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.info("Could not query the XMake compilers; keeping --lsp=$CLANGD_COMPILE_COMMANDS_LSP", error)
        return CLANGD_COMPILE_COMMANDS_LSP
    }
    val programs = parseCompilerPrograms(output)
    if (programs == null) {
        Log.info("Could not read the XMake compiler query; keeping --lsp=$CLANGD_COMPILE_COMMANDS_LSP")
        return CLANGD_COMPILE_COMMANDS_LSP
    }
    return if (programs.any(::isMsvcCompiler)) null else CLANGD_COMPILE_COMMANDS_LSP
}

/** Reads the JSON between the script's markers; null when the output does not carry it. */
internal fun parseCompilerPrograms(output: String): List<String>? {
    val text = output.replace(ANSI_ESCAPE, "")
    val begin = text.indexOf(COMPILER_QUERY_BEGIN)
    if (begin < 0) return null
    val end = text.indexOf(COMPILER_QUERY_END, begin + COMPILER_QUERY_BEGIN.length)
    if (end < 0) return null
    val payload = text.substring(begin + COMPILER_QUERY_BEGIN.length, end)
        .lineSequence()
        .map(String::trim)
        .lastOrNull { it.startsWith("[") }
        ?: return null
    return try {
        Json.parseToJsonElement(payload).jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        null
    }
}

/** `cl.exe` is the MSVC driver; `clang-cl.exe` only shares the suffix. */
internal fun isMsvcCompiler(program: String): Boolean =
    program.substringAfterLast('/').substringAfterLast('\\')
        .lowercase()
        .removeSuffix(".exe") == "cl"

private const val COMPILER_QUERY_BEGIN = "__begin__"
private const val COMPILER_QUERY_END = "__end__"

private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
