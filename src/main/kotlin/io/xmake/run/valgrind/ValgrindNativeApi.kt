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
package io.xmake.run.valgrind

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * The door to CLion's own Valgrind results panel: the panel, its tree, the console wrapper and
 * the data model live in CLion's memory profiling module, whose classes only a plugin that
 * depends on CLion can see. The optional `xmake-idea.clion-debug` module implements this
 * factory there; everywhere else, or when CLion does not provide the panel, the caller falls
 * the run presents its findings in the console instead.
 */
interface XMakeValgrindNativeSessionFactory {

    /** Opens the native session for a run, or returns null when it is not available. */
    fun open(project: Project): XMakeValgrindNativeSession?

    companion object {
        @JvmField
        val EP = ExtensionPointName<XMakeValgrindNativeSessionFactory>("io.xmake.valgrindNativeSession")
    }
}

/**
 * A native results session opened for one run: the panel and its model exist from the start of
 * the run and are filled while it runs, so the results follow the tool the way the native
 * runner's do.
 */
interface XMakeValgrindNativeSession {

    /**
     * Wraps the console of the run, so the platform hosts the console and the results panel in
     * one layout with the results as their own tab. Returns null when the wrapper cannot be
     * built; the run then keeps the plain console.
     */
    fun wrapConsole(project: Project, console: ConsoleView): ConsoleView?

    /**
     * Hands every error the report now has that the panel does not show yet to the model, so a
     * run in progress fills the panel as valgrind writes its report. Safe to call from any
     * thread with any snapshot of the report.
     */
    fun addNewErrors(errors: List<XMakeValgrindError>, toLocalPath: (String) -> String)
}

/** The first native session the installed modules can provide, or null. A factory that
 *  fails says so in the log; the next one, or the fallback panel, takes over. */
internal fun openXMakeValgrindNativeSession(project: Project): XMakeValgrindNativeSession? =
    XMakeValgrindNativeSessionFactory.EP.extensionList.firstNotNullOfOrNull { factory ->
        runCatching { factory.open(project) }.onFailure { error ->
            com.intellij.openapi.diagnostic.Logger.getInstance("io.xmake.run.valgrind")
                .warn("A native Valgrind session factory failed; falling back", error)
        }.getOrNull()
    }
