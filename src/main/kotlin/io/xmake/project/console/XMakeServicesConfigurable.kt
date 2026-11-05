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
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.xmake.utils.cli.XMakeServiceKind
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The build services' configuration: one section per kind, each with the endpoint it
 * connects to and the verbs that act on it.
 *
 * These are settings, not a monitor: a remote build host, a distcc cluster, a remote
 * cache are where the build goes, and the values persist per project. The live state
 * rides the status bar widget; this page owns what is written down.
 */
class XMakeServicesConfigurable(private val project: Project) : Configurable, Disposable {

    private val snapshotService = project.xmakeServicesSnapshots
    private val panels = XMakeServiceKind.entries.associateWith { XMakeServiceDetailPanel(project, it) }
    private val connection = project.messageBus.connect(this)

    private val root: JComponent by lazy {
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 16, 12, 0)
            XMakeServiceKind.entries.forEachIndexed { index, kind ->
                if (index > 0) add(sectionSeparator())
                add(section(kind))
            }
        }
    }

    init {
        connection.subscribe(XMakeServicesSnapshotService.TOPIC,
            XMakeServicesSnapshotService.Listener { render() })
        render()
    }

    override fun getDisplayName(): String = "Services"

    override fun createComponent(): JComponent = root

    override fun isModified(): Boolean = false

    override fun apply() {
        // Every edit lands through the snapshot service the moment its control is used;
        // the Settings contract still wants the entry points.
    }

    override fun reset() = snapshotService.refresh()

    override fun disposeUIResources() = render()

    override fun dispose() {}

    /** One kind's section: its name, what it is for, and the panel that edits it. */
    private fun section(kind: XMakeServiceKind): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel(kindName(kind)).apply {
            font = JBFont.label().biggerOn(1f).deriveFont(java.awt.Font.BOLD)
            alignmentX = LEFT_ALIGNMENT
        })
        add(JBLabel(
            when (kind) {
                XMakeServiceKind.REMOTE_BUILD -> "Build on a remote host over SSH; xmake service --connect --remote"
                XMakeServiceKind.DISTCC_BUILD -> "Distribute compilation across distcc hosts"
                XMakeServiceKind.REMOTE_CACHE -> "Reuse build artifacts from a remote cache"
            },
        ).apply {
            foreground = UIUtil.getLabelInfoForeground()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
        })
        add(panels.getValue(kind).apply { alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyTop(8) })
    }

    private fun sectionSeparator(): JComponent = JPanel().apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        preferredSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(20))
        maximumSize = preferredSize
    }

    /** Pushes the snapshot into the panels - the same render the contributor used, now
     *  on a page whose shape fits what the controls do. */
    private fun render() {
        val snapshot = snapshotService.snapshot
        val probing = snapshotService.probing
        XMakeServiceKind.entries.forEach { kind ->
            val state = snapshot?.services?.get(kind)
                ?: XMakeServiceState(kind, null, false, XMakeReachability.UNTESTED, false)
            panels.getValue(kind).apply {
                show(state, snapshot != null)
                setBusy(probing)
            }
        }
    }

    private companion object {
        private const val LEFT_ALIGNMENT = 0.0f
    }
}
