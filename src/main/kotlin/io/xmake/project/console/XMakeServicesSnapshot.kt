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
package io.xmake.project.console

import io.xmake.utils.cli.XMakeServiceKind
import io.xmake.utils.cli.markerPayload

/** How a service endpoint answered the probe's short TCP connect. */
enum class XMakeReachability {
    /** A TCP connection succeeded - the daemon is listening. */
    YES,

    /** The connect failed - the persisted state may be stale (e.g. the server rebooted). */
    NO,

    /** Nothing to connect to: no endpoint is configured for the kind. */
    UNTESTED,
}

/** One service kind as the probe saw it: what the client persists, plus the fresh liveness check. */
data class XMakeServiceState(
    val kind: XMakeServiceKind,
    /** The address the client would talk to, or null when none is configured. */
    val endpoint: String?,
    /** The `connected` flag persisted in the client's status file - the last known state, not a live one. */
    val connected: Boolean,
    val reachable: XMakeReachability,
    /** Whether a token is stored for the endpoint; without one a connect prompts on stdin. */
    val tokenKnown: Boolean,
)

/** The build cache half of the probe: the persisted option and the program xmake actually resolves. */
data class XMakeCacheState(
    /** The persisted `ccache` option; nil keeps xmake's on-by-default. */
    val enabled: Boolean,
    /** The resolved cache program path, or null when neither ccache nor sccache was found. */
    val program: String?,
)

/** What the Services panel shows between probes: one immutable probe result. */
data class XMakeServicesSnapshot(
    val cache: XMakeCacheState,
    val services: Map<XMakeServiceKind, XMakeServiceState>,
)

/** Parses the machine-readable payload the bundled probeservices.lua script prints between
 *  its `__begin__`/`__end__` markers (see the script header for the grammar). Anything xmake
 *  logs ahead of the markers is ignored, unknown service kinds are skipped for forward
 *  compatibility, and an output without markers answers null - the probe produced nothing. */
object XMakeServicesProbeParser {

    private val CACHE_ENABLED_LINE = Regex("ccache-enabled:\\s*(\\S+)")
    private val CACHE_PROGRAM_LINE = Regex("cache-program:\\s*(\\S.*)")
    private val SERVICE_LINE =
        Regex("service:\\s*kind=(\\S+)\\s+endpoint=(\\S+)\\s+connected=(\\S+)\\s+reachable=(\\S+)\\s+token=(\\S+)")

    fun parse(output: String): XMakeServicesSnapshot? {
        val payload = markerPayload(output) ?: return null
        val cache = XMakeCacheState(
            enabled = CACHE_ENABLED_LINE.find(payload)?.groupValues?.get(1) == "true",
            program = CACHE_PROGRAM_LINE.find(payload)?.groupValues?.get(1)
                ?.trim()
                ?.takeUnless { it == "none" || it.isEmpty() },
        )
        val services = SERVICE_LINE.findAll(payload)
            .mapNotNull { match ->
                val (kindName, endpoint, connected, reachable, token) = match.destructured
                val kind = XMakeServiceKind.entries.firstOrNull { it.statusDir == kindName }
                    ?: return@mapNotNull null
                XMakeServiceState(
                    kind = kind,
                    endpoint = endpoint.takeUnless { it == "none" },
                    connected = connected == "true",
                    reachable = when (reachable) {
                        "yes" -> XMakeReachability.YES
                        "no" -> XMakeReachability.NO
                        else -> XMakeReachability.UNTESTED
                    },
                    tokenKnown = token == "yes" || token == "true",
                )
            }
            .associateBy { it.kind }
        return XMakeServicesSnapshot(cache, services)
    }
}
