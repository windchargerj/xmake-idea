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
package io.xmake.debug.clion.valgrind

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

/**
 * The door to CLion's own Valgrind results panel: the panel, its tree, the console wrapper and
 * the data model live in CLion's memory profiling module, whose classes are reached through
 * CLion's own class loader. Where CLion does not provide the panel, the factory answers null
 * and the run presents its findings in the console instead.
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

/**
 * Opens the native session for a run, or null where this CLion cannot provide it. The call is
 * direct because both ends live in this module; the factory already answers every absence -
 * a CLion without the memory profiling module, an API that moved - with null and a log line.
 */
internal fun openXMakeValgrindNativeSession(project: Project): XMakeValgrindNativeSession? =
    ClionValgrindNativeSessionFactory.open(project)
