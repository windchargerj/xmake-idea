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

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.xmake.file.highlight.XMakeLuaLexer
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.run.command.query
import io.xmake.run.command.withProfileCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class XMakeInfoManager(private val project: Project, private val scope: CoroutineScope) {

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

            val info = project.withProfileCommands(profileSnapshot) { execution ->
                XMakeInfo().apply {
                    architectures = parseArchitectures(query("architectures", execution))
                    buildModes = parseBuildModes(query("buildmodes", execution))
                    platforms = parsePlatforms(query("platforms", execution))
                    toolchains = parseToolchains(query("toolchains", execution))
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

    fun refreshSyntaxInfo(profile: XMakeBuildProfile?) {
        syntaxRefresh?.cancel()
        val requestId = syntaxRequestId.incrementAndGet()
        val profileSnapshot = profile?.copy()?.takeIf { buildProfileInfoKey(it) != null }
        syntaxRefresh = profileSnapshot?.let { selectedProfile ->
            scope.launch(Dispatchers.IO) {
                try {
                    val apis = project.withProfileCommands(selectedProfile) { execution ->
                        XMakeInfo().parseApis(query("apis", execution))
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

    private fun buildProfileInfoKey(profile: XMakeBuildProfile): BuildProfileInfoKey? {
        val toolkit = profile.resolveToolkit(project) ?: return null
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

    companion object {
        private val Log = logger<XMakeInfoManager>()

        fun getInstance(project: Project): XMakeInfoManager =
            project.serviceOrNull() ?: error("Failed to get XMakeInfoManager for $project")
    }
}
