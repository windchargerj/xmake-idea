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
package io.xmake.project.profile

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.xmake.project.toolkit.Toolkit
import io.xmake.run.command.XMakeProjectVersionError
import io.xmake.run.command.userMessage
import io.xmake.run.command.withProfileCommands
import io.xmake.utils.info.XMakeArchitectures
import io.xmake.utils.info.XMakeBuildModes
import io.xmake.utils.info.XMakeInfo
import io.xmake.utils.info.XMakePlatforms
import io.xmake.utils.info.XMakeToolchains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal data class XMakeBuildOptions(
    val architectures: XMakeArchitectures = emptyMap(),
    val buildModes: XMakeBuildModes = emptyList(),
    val platforms: XMakePlatforms = emptyList(),
    val toolchains: XMakeToolchains = emptyMap(),
    /** The wording of [XMakeProjectVersionError.userMessage] when the project script refuses
     *  this xmake version; it replaces the options, and the four lists are then meaningless. */
    val versionRefusal: String? = null,
    /** The xmake version the project requires whenever [versionError] is set; the form shows
     *  its own short line from it. */
    val requiredVersion: String? = null,
)

/** Queries the build options available in one build profile's command context. The profile only
 *  supplies the command context (its XMAKE_CONFIGDIR is profile-scoped); `xmake show -l` listings
 *  do not read it, so any profile bound to the toolkit yields the same listings. */
internal suspend fun Project.queryXMakeBuildOptions(
    profile: XMakeBuildProfile,
    toolkit: Toolkit,
): XMakeBuildOptions {
    val capturedError = AtomicReference<XMakeProjectVersionError>()
    val options = withContext(Dispatchers.IO) {
        withProfileCommands(profile, toolkit) {
            coroutineScope {
                // A version refusal of the project script fails only the listings that load the
                // project; the listings xmake answers from its static tables still arrive.
                suspend fun <T> listingOrNullIfRefused(name: String, parse: (String) -> T): T? =
                    try {
                        listing(name, parse)
                    } catch (error: XMakeProjectVersionError) {
                        capturedError.compareAndSet(null, error)
                        null
                    }
                val architectures = async { listingOrNullIfRefused("architectures", XMakeInfo::parseArchitectures) }
                val buildModes = async { listingOrNullIfRefused("buildmodes", XMakeInfo::parseBuildModes) }
                val platforms = async { listingOrNullIfRefused("platforms", XMakeInfo::parsePlatforms) }
                val toolchains = async { listingOrNullIfRefused("toolchains", XMakeInfo::parseToolchains) }
                XMakeBuildOptions(
                    architectures = architectures.await()?.toSortedMap() ?: emptyMap(),
                    buildModes = buildModes.await()?.distinct()?.sorted() ?: emptyList(),
                    platforms = platforms.await()?.distinct()?.sorted() ?: emptyList(),
                    toolchains = toolchains.await()?.toSortedMap() ?: emptyMap(),
                    versionRefusal = capturedError.get()?.userMessage(toolkit),
                    requiredVersion = capturedError.get()?.requiredVersion,
                )
            }
        }
    }
    capturedError.get()?.let { Log.warn(it.userMessage(toolkit)) }
    return options
}

private val Log = logger<XMakeBuildOptions>()
