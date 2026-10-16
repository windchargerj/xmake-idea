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
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.xmake.run.valgrind.XMakeValgrindError
import io.xmake.run.valgrind.XMakeValgrindFrame
import io.xmake.run.valgrind.XMakeValgrindNativeSession
import io.xmake.run.valgrind.XMakeValgrindNativeSessionFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import javax.swing.Icon

/**
 * Opens CLion's own Valgrind results panel for a run: the panel, its tree, the console wrapper
 * and the data model are the ones the native runner shows, down to their icons and their
 * context menu.
 *
 * The machinery lives in CLion's memory profiling module, which JetBrains marks with internal
 * visibility: a plugin cannot depend on the module (the platform refuses the dependency) and
 * the classes do not cross into other plugins' class loaders, so the panel is reached through
 * CLion's own class loader instead, the loader those classes live in. Every failure, a CLion
 * without the module, an IDE that is not CLion, a release that moves the API, is answered
 * with null and the caller falls back to the platform-only panel.
 *
 * The model is filled from the plugin's own parse of the report rather than through the native
 * reader: that reader resolves the tool environment from the project's selected CMake run
 * configuration, which an xmake project does not have, and the environment only ever maps the
 * paths of frames, which the run's own toolkit mapping does instead.
 */
class ClionValgrindNativeSessionFactory : XMakeValgrindNativeSessionFactory {

    override fun open(project: Project): XMakeValgrindNativeSession? = runCatching {
        // The panel installs its context menu from this action group, registered by the memory
        // profiling module itself; without it the native path is not usable.
        if (ActionManager.getInstance().getAction(TREE_POPUP_ID) == null) return null
        val native = NativeMemoryProfileClasses.load() ?: return null
        ClionValgrindNativeSession(project, native)
    }.onFailure { error ->
        Log.warn("The native Valgrind panel is unavailable; using the platform-only panel", error)
    }.getOrNull()

    private companion object {
        private val Log = Logger.getInstance(ClionValgrindNativeSessionFactory::class.java)

        private const val TREE_POPUP_ID = "ValgrindToolWindow.TreePopup"
    }
}

/** One run's native session: the model, the panel and the console wrapper, opened together. */
private class ClionValgrindNativeSession(
    project: Project,
    private val native: NativeMemoryProfileClasses,
) : XMakeValgrindNativeSession {

    private val model = native.newModel(project)
    private val panel = native.newPanel(project, model)

    /** How many errors the model already shows; touched on the EDT only. */
    private var reportedErrors = 0

    override fun wrapConsole(project: Project, console: ConsoleView): ConsoleView? =
        runCatching { native.newConsoleWrapper(project, console, panel) }
            .onFailure { error -> Log.warn("The native console wrapper is unavailable", error) }
            .getOrNull()

    override fun addNewErrors(errors: List<XMakeValgrindError>, toLocalPath: (String) -> String) {
        if (errors.size <= reportedErrors) return
        ApplicationManager.getApplication().invokeLater {
            if (errors.size <= reportedErrors) return@invokeLater
            errors.drop(reportedErrors).forEach { error -> native.addError(model, error, toLocalPath) }
            reportedErrors = errors.size
        }
    }

    private companion object {
        private val Log = Logger.getInstance(ClionValgrindNativeSession::class.java)
    }
}

/**
 * The native panel classes, resolved in CLion's class loader, the one loader they are visible
 * in. The platform types the constructors take, [Project], [com.intellij.openapi.actionSystem.AnAction],
 * [ConsoleView], [javax.swing.Icon], live in the shared parent, so instances created here pass
 * between the loaders freely.
 */
private class NativeMemoryProfileClasses private constructor(
    modelClass: Class<*>,
    panelClass: Class<*>,
    errorClass: Class<*>,
    descriptionClass: Class<*>,
    stackClass: Class<*>,
    frameClass: Class<*>,
    settingsActionClass: Class<*>,
    consoleWrapperClass: Class<*>,
) {

    private val modelConstructor = modelClass.getConstructor(String::class.java, Project::class.java)
    private val addError = modelClass.getMethod("addError", errorClass)
    private val errorConstructor = errorClass.getDeclaredConstructor()
    private val setKind = errorClass.getMethod("setKind", String::class.java)
    private val setSuppression = errorClass.getMethod("setSuppression", String::class.java)
    private val getProblemDescriptions = errorClass.getMethod("getProblemDescriptions")
    private val descriptionConstructor = descriptionClass.getConstructor(String::class.java)
    private val getStack = descriptionClass.getMethod("getStack")
    private val pushFrame = stackClass.getMethod("pushFrame", frameClass)
    private val frameConstructor = frameClass.getDeclaredConstructor()
    private val setFrameInstructionPointer = frameClass.getMethod("setInstructionPointer", String::class.java)
    private val setFrameBinary = frameClass.getMethod("setBinary", String::class.java)
    private val setFrameFunction = frameClass.getMethod("setFunction", String::class.java)
    private val setFrameDirectory = frameClass.getMethod("setDirectory", String::class.java)
    private val setFrameResolvedDirectory = frameClass.getMethod("setResolvedLocalDirectory", String::class.java)
    private val setFrameFile = frameClass.getMethod("setFile", String::class.java)
    private val setFrameLine = frameClass.getMethod("setLine", String::class.java)
    private val panelConstructor: Constructor<*> = panelClass.getConstructor(
        modelClass,
        AnAction::class.java,
        String::class.java,
        Project::class.java,
    )
    private val settingsActionConstructor = settingsActionClass.getDeclaredConstructor()
    private val consoleWrapperConstructor = consoleWrapperClass.getConstructor(
        String::class.java,
        ConsoleView::class.java,
        panelClass,
        Project::class.java,
        Icon::class.java,
    )

    fun newModel(project: Project): Any = modelConstructor.newInstance(PROFILER_NAME, project)

    /** The panel takes the action its settings button runs and the id of its tree context menu;
     *  both are the native ones. */
    fun newPanel(project: Project, model: Any): Any =
        panelConstructor.newInstance(model, settingsActionConstructor.newInstance(), TREE_POPUP_ID, project)

    /** The native console wrapper: hosts the console and the panel in the run content and makes
     *  the panel tab bounce when the model gains errors. */
    fun newConsoleWrapper(project: Project, console: ConsoleView, panel: Any): ConsoleView =
        consoleWrapperConstructor.newInstance(PROFILER_NAME, console, panel, project, PANEL_ICON) as ConsoleView

    /** Hands one error to the native model the way the native reader does: no caption, so the
     *  first description titles the error, with its stack and every further description below. */
    fun addError(model: Any, error: XMakeValgrindError, toLocalPath: (String) -> String) {
        val nativeError = errorConstructor.newInstance()
        setKind.invoke(nativeError, error.kind)
        if (error.suppression.isNotEmpty()) {
            setSuppression.invoke(nativeError, error.suppression)
        }
        @Suppress("UNCHECKED_CAST")
        val descriptions = getProblemDescriptions.invoke(nativeError) as MutableList<Any>
        error.descriptions.forEach { description ->
            val nativeDescription = descriptionConstructor.newInstance(description.text)
            val stack = getStack.invoke(nativeDescription)
            description.frames.forEach { frame -> pushFrame.invoke(stack, newFrame(frame, toLocalPath)) }
            descriptions.add(nativeDescription)
        }
        addError.invoke(model, nativeError)
    }

    private fun newFrame(frame: XMakeValgrindFrame, toLocalPath: (String) -> String): Any {
        val native = frameConstructor.newInstance()
        val localDirectory = toLocalPath(frame.directory)
        setFrameInstructionPointer.invoke(native, frame.instructionPointer)
        setFrameBinary.invoke(native, toLocalPath(frame.binary))
        setFrameFunction.invoke(native, frame.function)
        setFrameDirectory.invoke(native, localDirectory)
        // The resolved directory is what the native navigation and preview join the file name
        // with, so it must be the local form.
        setFrameResolvedDirectory.invoke(native, localDirectory.takeIf(String::isNotEmpty))
        setFrameFile.invoke(native, frame.file)
        setFrameLine.invoke(native, frame.line?.toString().orEmpty())
        return native
    }

    companion object {

        private const val PROFILER_NAME = "Valgrind"

        private const val TREE_POPUP_ID = "ValgrindToolWindow.TreePopup"

        /** The icon the native runner puts on the results tab. */
        private val PANEL_ICON = AllIcons.Actions.StartMemoryProfile

        /**
         * The classes of the memory profiling module, in the loader of the plugin that ships
         * them; null where that plugin or its classes are absent. The tree popup action the
         * panel needs is registered by that same plugin, so the action's own class carries the
         * right loader here, without reaching for internal plugin manager APIs.
         */
        fun load(): NativeMemoryProfileClasses? {
            val action = ActionManager.getInstance().getAction(TREE_POPUP_ID) ?: return null
            val loader = action.javaClass.classLoader
            return runCatching {
                NativeMemoryProfileClasses(
                    modelClass = forName(loader, MEMORY + "MemoryProfileTreeDataModel"),
                    panelClass = forName(loader, MEMORY + "ui.MemoryProfileOutputPanel"),
                    errorClass = forName(loader, MEMORY + "error.MemoryProfileError"),
                    descriptionClass = forName(loader, MEMORY + "error.MemoryProfileProblemDescription"),
                    stackClass = forName(loader, MEMORY + "error.MemoryProfileStack"),
                    frameClass = forName(loader, MEMORY + "error.MemoryProfileFrame"),
                    settingsActionClass = forName(loader, VALGRIND + "actions.EditValgrindSettingsAction"),
                    consoleWrapperClass = forName(loader, MEMORY + "MemoryProfileConsoleViewWrapper"),
                )
            }.getOrNull()
        }

        private fun forName(loader: ClassLoader, name: String): Class<*> = Class.forName(name, true, loader)

        private const val MEMORY = "com.intellij.cidr.profiling.memory."

        private const val VALGRIND = "com.intellij.cidr.profiling.valgrind."
    }
}
