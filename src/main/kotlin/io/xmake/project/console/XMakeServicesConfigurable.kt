/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License 2.0 (the "License");
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

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import javax.swing.JComponent
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import io.xmake.utils.cli.XMakeServiceKind

/**
 * The build services' configuration: one section per kind, each with the endpoint it
 * connects to and the connect and disconnect verbs, on the platform's own settings
 * builder - rows, captions, and spacing are its, not ours to align.
 *
 * These are settings, not a monitor: a remote build host, a distcc cluster, a remote
 * cache are where the build goes, and the values persist per project. The live state
 * rides the status-bar widget.
 */
class XMakeServicesConfigurable(private val valProject: Project) : Configurable, Disposable {

    private val snapshotService = valProject.xmakeServicesSnapshots

    /** The panel is built once per page open; the DSL owns its layout. */
    private var page: DialogPanel? = null

    override fun getDisplayName(): String = "Services"

    override fun createComponent(): JComponent {
        val built = buildPage()
        page = built
        return built
    }

    override fun isModified(): Boolean = page?.isModified() ?: false

    override fun apply() {
        page?.apply()
    }

    override fun reset() {
        page?.reset()
        snapshotService.refresh()
    }

    override fun disposeUIResources() {
        page = null
    }

    override fun dispose() {}

    private fun buildPage(): DialogPanel {
        val snapshots = snapshotService.snapshot
        return panel {
            XMakeServiceKind.entries.forEach { kind ->
                val state = snapshots?.services?.get(kind)
                    ?: XMakeServiceState(kind, null, false, XMakeReachability.UNTESTED, false)
                group(kindName(kind)) {
                    row {
                        comment(
                            when (kind) {
                                XMakeServiceKind.REMOTE_BUILD -> "Build on a remote host over SSH"
                                XMakeServiceKind.DISTCC_BUILD -> "Distribute compilation across distcc hosts"
                                XMakeServiceKind.REMOTE_CACHE -> "Reuse build artifacts from a remote cache"
                            },
                        )
                    }
                    lateinit var field: com.intellij.ui.components.JBTextField
                    row("Endpoint:") {
                        val initial = state.endpoint
                            ?: valProject.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(kind.name)
                            ?: ""
                        field = com.intellij.ui.components.JBTextField(initial)
                        cell(field).align(com.intellij.ui.dsl.builder.AlignX.FILL)
                            .comment("host:port")
                    }
                    // The endpoint lands the moment it changes - the same live-apply the
                    // tree panel uses; Apply on the page has nothing left to hold.
                    field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                        override fun insertUpdate(event: javax.swing.event.DocumentEvent) = changed()
                        override fun removeUpdate(event: javax.swing.event.DocumentEvent) = changed()
                        override fun changedUpdate(event: javax.swing.event.DocumentEvent) = changed()

                        private fun changed() {
                            val entered = field.text.trim()
                            val stored = valProject.getService(XMakeServiceEndpointsService::class.java)
                                .configuredEndpoint(kind.name)
                            if (entered == stored) return
                            if (entered.isEmpty()) {
                                snapshotService.clearEndpoint(kind.name)
                            } else {
                                snapshotService.applyEndpoint(kind.name, kind, entered)
                            }
                        }
                    })
                    row {
                        button("Connect") { snapshotService.connectService(kind) }
                        button("Disconnect") { snapshotService.disconnectService(kind) }
                        button("Clear endpoint") { snapshotService.clearEndpoint(kind.name) }
                    }
                }
            }
            row {
                comment(
                    snapshotService.statusMessage
                        ?: "The status also rides the status bar; showing the XMake tool window re-probes.",
                )
            }
        }
    }
}
