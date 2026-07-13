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
 * @file        DebugModuleLoader.kt
 *
 */
package io.xmake.debug

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import io.xmake.utils.Logger

/**
 * Dynamic bridge to the optional CLion debug module.
 */
object DebugModuleLoader {

    private const val TAG = "DebugModuleLoader"
    private const val DEBUG_CONTENT_MODULE_NAME = "xmake-idea.clion-debug"
    private const val DEBUG_MODULE_CLASS_NAME = "io.xmake.debug.clion.ClionDebugModule"

    @Volatile
    private var debugModuleClass: Class<*>? = null
    @Volatile
    private var loadAttempted = false

    /**
     * Resolve and cache the optional debug module if it is available.
     */
    fun loadDebugModuleIfNeeded(): Boolean {
        if (loadAttempted) {
            return debugModuleClass != null
        }

        return synchronized(this) {
            if (loadAttempted) {
                debugModuleClass != null
            } else {
                val loaded = try {
                    loadDebugModule()
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load debug module", e)
                    false
                }
                loadAttempted = true
                loaded
            }
        }
    }

    /**
     * Load the debug module class from the plugin classpath or packaged content module jar.
     */
    private fun loadDebugModule(): Boolean {
        debugModuleClass = resolveDebugModuleClass()
            ?: loadDebugModuleFromContentModule()

        if (debugModuleClass == null) {
            Logger.w(TAG, "Debug module class not found: $DEBUG_MODULE_CLASS_NAME")
            return false
        }

        Logger.d(TAG, "Debug module loaded successfully")
        return true
    }

    private fun resolveDebugModuleClass(classLoader: ClassLoader? = this::class.java.classLoader): Class<*>? {
        if (classLoader == null) {
            return null
        }
        return try {
            Class.forName(DEBUG_MODULE_CLASS_NAME, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            Logger.d(TAG, "Debug module class is not linkable from classpath: ${e.message}")
            null
        }
    }

    private fun loadDebugModuleFromContentModule(): Class<*>? {
        val rootDescriptor = (DebugModuleLoader::class.java.classLoader as? PluginAwareClassLoader)
            ?.pluginDescriptor
        if (rootDescriptor == null) {
            Logger.d(TAG, "Root plugin descriptor not found")
            return null
        }

        val classLoader = ContentModuleClassLoaderResolver.resolve(rootDescriptor, DEBUG_CONTENT_MODULE_NAME)
        if (classLoader == null) {
            Logger.d(TAG, "Content module class loader not found: $DEBUG_CONTENT_MODULE_NAME")
            return null
        }

        return resolveDebugModuleClass(classLoader)
    }

    /**
     * Create a debug process using the loaded CLion module.
     */
    fun createDebugProcess(
        project: Project,
        driverName: String,
        driverPath: String,
        launchConfig: String,
        targetPath: String,
        workingDir: String,
        session: XDebugSession,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap()
    ): XDebugProcess? {
        if (debugModuleClass == null) {
            Logger.w(TAG, "Debug module not loaded")
            return null
        }

        return try {
            val createProcessMethod = debugModuleClass?.getMethod(
                "createDebugProcess",
                Project::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                XDebugSession::class.java,
                List::class.java,
                Map::class.java
            )
            val result = createProcessMethod?.invoke(
                null,
                project,
                driverName,
                driverPath,
                launchConfig,
                targetPath,
                workingDir,
                session,
                args,
                env
            )
            result as? XDebugProcess
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create debug process", e)
            null
        }
    }

    /**
     * Check if debugging is available.
     */
    fun isDebuggingAvailable(project: Project): Boolean {
        if (debugModuleClass == null) {
            return false
        }

        return try {
            val isAvailableMethod = debugModuleClass?.getMethod("isDebuggingAvailable", Project::class.java)
            val result = isAvailableMethod?.invoke(null, project)
            result as? Boolean ?: false
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to check debugging availability", e)
            false
        }
    }

    /**
     * Reset the loaded debug module state.
     */
    @Synchronized
    fun unloadDebugModule() {
        debugModuleClass = null
        loadAttempted = false
        Logger.d(TAG, "Debug module unloaded")
    }
}
