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
 * @file        XMakeInfoManager.kt
 *
 */
package io.xmake.utils.info

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.xmake.file.highlight.XMakeLuaLexer
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.toolkit.Toolkit
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.xmakeExecutionService
import io.xmake.utils.execute.createProcess
import io.xmake.utils.execute.runProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class XMakeInfoManager(val project: Project, private val scope: CoroutineScope) {

    val xmakeInfo: XMakeInfo = XMakeInfo()

    // Todo
    val cachedXMakeInfoMap: MutableMap<Toolkit, XMakeInfo> = mutableMapOf()

    private val buildProfileInfoCache = ConcurrentHashMap<BuildProfileInfoKey, XMakeInfo>()
    private val buildProfileInfoMutex = Mutex()
    private var buildProfilePreload: Job? = null
    private var buildProfilePreloadKey: BuildProfileInfoKey? = null
    private var syntaxRefresh: Job? = null
    private val syntaxRequestId = AtomicLong()

    suspend fun loadBuildProfileInfo(profile: XMakeBuildProfile): XMakeInfo = withContext(Dispatchers.IO) {
        val profileSnapshot = profile.copy()
        val cacheKey = buildProfileInfoKey(profileSnapshot)
        if (cacheKey != null) {
            buildProfileInfoCache[cacheKey]?.let { return@withContext it }
        }

        buildProfileInfoMutex.withLock {
            if (cacheKey != null) {
                buildProfileInfoCache[cacheKey]?.let { return@withLock it }
            }

            val info = withProfileCommands(profileSnapshot) {
                XMakeInfo().apply {
                    architectures = parseArchitectures(query("architectures"))
                    buildModes = parseBuildModes(query("buildmodes"))
                    platforms = parsePlatforms(query("platforms"))
                    toolchains = parseToolchains(query("toolchains"))
                }
            }
            if (cacheKey != null) {
                buildProfileInfoCache.keys.removeIf { key ->
                    key.profileId == cacheKey.profileId && key != cacheKey
                }
                buildProfileInfoCache[cacheKey] = info
            }
            info
        }
    }

    fun preloadBuildProfileInfo(profile: XMakeBuildProfile?) {
        val profileSnapshot = profile?.copy()
        val cacheKey = profileSnapshot?.let(::buildProfileInfoKey)
        if (
            cacheKey != null &&
            cacheKey == buildProfilePreloadKey &&
            buildProfilePreload?.isActive == true
        ) {
            return
        }

        buildProfilePreload?.cancel()
        if (profileSnapshot == null || cacheKey == null || buildProfileInfoCache.containsKey(cacheKey)) {
            buildProfilePreload = null
            buildProfilePreloadKey = null
            return
        }
        buildProfilePreloadKey = cacheKey
        buildProfilePreload = scope.launch(Dispatchers.IO) {
            try {
                loadBuildProfileInfo(profileSnapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.warn("Failed to preload XMake information for profile ${profileSnapshot.id}", error)
            }
        }
    }

    suspend fun loadTargets(profile: XMakeBuildProfile): List<String> = withContext(Dispatchers.IO) {
        val profileSnapshot = profile.copy()
        withProfileCommands(profileSnapshot) {
            configureForTargetDiscovery()
            XMakeInfo().parseTargets(query("targets"))
        }
    }

    fun refreshSyntaxInfo(profile: XMakeBuildProfile?) {
        syntaxRefresh?.cancel()
        val requestId = syntaxRequestId.incrementAndGet()
        val profileSnapshot = profile?.copy()?.takeIf { buildProfileInfoKey(it) != null }
        syntaxRefresh = profileSnapshot?.let { selectedProfile ->
            scope.launch(Dispatchers.IO) {
                try {
                    val apis = withProfileCommands(selectedProfile) {
                        XMakeInfo().parseApis(query("apis"))
                    }
                    if (requestId == syntaxRequestId.get() && !project.isDisposed && apis.isNotEmpty()) {
                        XMakeLuaLexer.updateApis(apis)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.warn("Failed to load XMake API information for profile ${selectedProfile.id}", error)
                }
            }
        }
    }

    private suspend fun <T> withProfileCommands(
        profile: XMakeBuildProfile,
        action: suspend XMakeCommandFactory.() -> T,
    ): T = project.xmakeExecutionService.runExclusive {
        val commands = XMakeCommandFactory(project, profile)
        commands.action()
    }

    private suspend fun XMakeCommandFactory.configureForTargetDiscovery() {
        val command = createConfigure()
        try {
            project.xmakeExecutionService.execute(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.debug(
                "XMake configuration failed during target discovery; querying declared targets instead: " +
                    command.commandLine.commandLineString,
                error,
            )
        }
    }

    private suspend fun XMakeCommandFactory.query(name: String): String {
        val command = createInfoQuery(name)
        return try {
            project.xmakeExecutionService.captureStandardOutput(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.warn("XMake info query failed: ${command.commandLine.commandLineString}", error)
            throw error
        }
    }

    private fun buildProfileInfoKey(profile: XMakeBuildProfile): BuildProfileInfoKey? {
        val toolkit = profile.resolveToolkit() ?: return null
        val workingDirectory = try {
            profile.resolveWorkingDirectory(project, toolkit)
        } catch (_: RuntimeConfigurationError) {
            return null
        }
        return BuildProfileInfoKey(
            profileId = profile.id,
            toolkitId = toolkit.id,
            toolkitPath = toolkit.path,
            toolkitVersion = toolkit.version,
            toolkitEndpoint = toolkit.host.endpointIdentity,
            workingDirectory = workingDirectory,
            configureInputs = listOf(
                profile.platform,
                profile.architecture,
                profile.toolchain,
                profile.mode,
                profile.buildDirectory,
                profile.androidNdkDirectory,
                profile.additionalConfiguration,
            ),
        )
    }

    private data class BuildProfileInfoKey(
        val profileId: String,
        val toolkitId: String,
        val toolkitPath: String,
        val toolkitVersion: String,
        val toolkitEndpoint: String,
        val workingDirectory: String,
        val configureInputs: List<String>,
    )

    fun probeXMakeInfo(toolkit: Toolkit?) {
        scope.launch {
            toolkit?.let {
                val workingDirectory = project.basePath?.let { path -> File(path) }

                suspend fun runXMakeShow(key: String): String {
                    val cmd = GeneralCommandLine(
                        "xmake show -l $key --json".split(" ")
                    ).apply {
                        workingDirectory?.let { wd -> withWorkDirectory(wd) }
                        withEnvironment("XMAKE_SKIP_HISTORY", "1")
                        withEnvironment("XMAKE_ROOT", "y")
                        withEnvironment("XMAKE_COLOR_TERM", "nocolor")
                    }
                    val result = runProcess(cmd.createProcess(it)).first.getOrDefault("")
                    return result
                }

                val architecturesString = runXMakeShow("architectures")
                val buildModesString = runXMakeShow("buildmodes")
                val platformsString = runXMakeShow("platforms")
                val targetsString = runXMakeShow("targets")
                val toolchainsString = runXMakeShow("toolchains")

                with(xmakeInfo) {
                    architectures = parseArchitectures(architecturesString)
                    buildModes = parseBuildModes(buildModesString)
                    platforms = parsePlatforms(platformsString)
                    targets = parseTargets(targetsString)
                    toolchains = parseToolchains(toolchainsString)
                }

                project.messageBus.syncPublisher(XMAKE_INFO_TOPIC).onXMakeInfoUpdated(xmakeInfo)
            }
        }
    }

    fun probeXMakeApis(toolkit: Toolkit?) {
        scope.launch {
            toolkit?.let {
                val workingDirectory = project.basePath?.let { path -> File(path) }

                suspend fun runXMakeShow(key: String): String {
                    val cmd = GeneralCommandLine(
                        "xmake show -l $key --json".split(" ")
                    ).apply {
                        workingDirectory?.let { wd -> withWorkDirectory(wd) }
                        withEnvironment("XMAKE_SKIP_HISTORY", "1")
                        withEnvironment("XMAKE_ROOT", "y")
                        withEnvironment("XMAKE_COLOR_TERM", "nocolor")
                    }
                    val result = runProcess(cmd.createProcess(it)).first.getOrDefault("")
                    return result
                }

                val apisString = runXMakeShow("apis")

                with(xmakeInfo) {
                    apis = parseApis(apisString)
                }

                if (xmakeInfo.apis.isNotEmpty()) {
                    XMakeLuaLexer.updateApis(xmakeInfo.apis)
                }
            }
        }
    }

    interface XMakeInfoListener {
        fun onXMakeInfoUpdated(xmakeInfo: XMakeInfo)
    }

    companion object {
        val Log = logger<XMakeInfoManager>()
        val XMAKE_INFO_TOPIC = Topic.create("XMake Info Updated", XMakeInfoListener::class.java)

        fun getInstance(project: Project): XMakeInfoManager = project.serviceOrNull() ?: throw IllegalStateException()
    }
}
