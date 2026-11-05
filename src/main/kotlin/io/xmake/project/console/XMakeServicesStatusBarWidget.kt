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
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import io.xmake.utils.cli.XMakeServiceKind
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * The build services where a reader looks during work: one status-bar entry that says
 * whether the build goes anywhere but this machine, and, on click, the verbs for each
 * kind plus the road to its configuration.
 *
 * A connection is a state of the build, not a process to watch, so it lives beside the
 * other build indicators rather than in a window of its own; the values behind it live
 * in Settings (the Services page).
 */
class XMakeServicesStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = XMakeServicesStatusBarWidget.ID

    override fun getDisplayName(): String = "XMake Build Services"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = XMakeServicesStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
}

internal class XMakeServicesStatusBarWidget(private val project: Project) : StatusBarWidget,
    StatusBarWidget.TextPresentation, Disposable {

    private var statusBar: StatusBar? = null
    private val connection = project.messageBus.connect(this)

    init {
        connection.subscribe(XMakeServicesSnapshotService.TOPIC,
            XMakeServicesSnapshotService.Listener { statusBar?.updateWidget(ID) })
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {}

    override fun getText(): String {
        val snapshot = project.xmakeServicesSnapshots.snapshot ?: return "xmake: …"
        val connected = XMakeServiceKind.entries.filter { snapshot.services[it]?.connected == true }
        return when {
            connected.isEmpty() -> "xmake: local build"
            else -> "xmake: " + connected.joinToString(", ") { shortName(it) }
        }
    }

    override fun getTooltipText(): String {
        val snapshot = project.xmakeServicesSnapshots.snapshot
            ?: return "Reading the build services - click to probe"
        return XMakeServiceKind.entries.joinToString("\n") { kind ->
            val state = snapshot.services[kind]
            "${kindName(kind)}: ${state?.let { serviceStatusText(it, true) } ?: "unknown"}"
        }
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        // The click is the probe: the menu is built from the freshest snapshot, and the
        // widget repaints when the answer lands.
        project.xmakeServicesSnapshots.refresh()
        showMenu(event)
    }

    private fun showMenu(event: MouseEvent) {
        val snapshot = project.xmakeServicesSnapshots.snapshot
        val menu = JPopupMenu()
        XMakeServiceKind.entries.forEach { kind ->
            val state = snapshot?.services?.get(kind)
            val item = JMenuItem(
                when {
                    state?.connected == true -> "Disconnect ${kindName(kind)}"
                    state?.endpoint != null -> "Connect ${kindName(kind)}"
                    else -> "${kindName(kind)} - not configured"
                },
            )
            item.isEnabled = when {
                state?.connected == true -> true
                state?.endpoint != null -> true
                else -> false
            }
            item.addActionListener {
                if (state?.connected == true) {
                    project.xmakeServicesSnapshots.disconnectService(kind)
                } else {
                    project.xmakeServicesSnapshots.connectService(kind)
                }
            }
            menu.add(item)
        }
        menu.addSeparator()
        menu.add(JMenuItem("Configure…").apply {
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, XMakeServicesConfigurable::class.java)
            }
        })
        menu.show(event.component, event.x, event.y)
    }

    private fun shortName(kind: XMakeServiceKind): String = when (kind) {
        XMakeServiceKind.REMOTE_BUILD -> "remote"
        XMakeServiceKind.DISTCC_BUILD -> "distcc"
        XMakeServiceKind.REMOTE_CACHE -> "cache"
    }

    companion object {
        const val ID: String = "XMakeServices"
    }
}
