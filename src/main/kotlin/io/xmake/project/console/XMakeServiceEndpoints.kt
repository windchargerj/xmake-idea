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

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.xmake.utils.cli.XMakeServiceKind

/** What an editable service endpoint may look like: `addr:port` with a numeric port,
 *  the one shape xmake's own client accepts in client.conf (`socket.connect` splits on
 *  the colon). Hostnames and IPv4 literals both pass - the resolve happens on the
 *  toolkit's host. Kept platform-free so it stays unit-testable. */
object XMakeServiceEndpointFormat {

    /** The endpoint as the pair xmake talks to, or null when [raw] is not `addr:port`. */
    fun parse(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        val colon = text.lastIndexOf(':')
        if (colon <= 0 || colon == text.length - 1) {
            return null
        }
        val host = text.substring(0, colon).trim()
        val port = text.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) {
            return null
        }
        return host to port
    }
}

/**
 * The endpoints the user configured from the IDE, persisted per project so the editor
 * survives restarts (`xmake.xml`, the repo's own project settings file).
 *
 * This store is deliberately NOT a second source of truth for what the services are
 * connected to: xmake owns that state (client.conf / status.txt, possibly edited in a
 * terminal or on a remote host). What is stored here is what the IDE last *set*, and
 * it is materialized into xmake's own project-local client.conf through the executor
 * channel (the setservice.lua script) at apply time - never consulted as the current
 * state. The probe remains the only thing the panels display.
 */
@Service(Service.Level.PROJECT)
@State(name = "XMakeServiceEndpoints", storages = [Storage("xmake.xml")])
class XMakeServiceEndpointsService : PersistentStateComponent<XMakeServiceEndpointsService.State> {

    class State {
        /** Keyed by [XMakeServiceKind.name]; XmlSerializer round-trips the string map. */
        var endpoints: MutableMap<String, String> = mutableMapOf()
    }

    private val lock = Any()
    private var configured: Map<XMakeServiceKind, String> = emptyMap()

    /** What the IDE last set for [kind], or null when the user has not configured one. */
    fun configuredEndpoint(kind: XMakeServiceKind): String? = synchronized(lock) {
        configured[kind]
    }

    /** Records [endpoint] as the IDE-side configuration of [kind]; null clears the record. */
    fun setConfiguredEndpoint(kind: XMakeServiceKind, endpoint: String?) {
        synchronized(lock) {
            val updated = configured.toMutableMap()
            if (endpoint == null) updated.remove(kind) else updated[kind] = endpoint
            configured = updated
        }
    }

    override fun getState(): State = synchronized(lock) {
        State().also { state ->
            configured.forEach { (kind, endpoint) -> state.endpoints[kind.name] = endpoint }
        }
    }

    override fun loadState(state: State) {
        synchronized(lock) {
            configured = state.endpoints.mapNotNull { (name, endpoint) ->
                XMakeServiceKind.entries.firstOrNull { it.name == name }?.let { it to endpoint }
            }.toMap()
        }
    }
}
