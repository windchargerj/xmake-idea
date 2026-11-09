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
package io.xmake.utils.ui

import com.intellij.ui.JBColor
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/** One badge: a pill carrying a word - the state, or a platform the package answers -
 *  attached to the name, the way the Marketplace header carries its tags.
 *
 *  The fill is the tone at a low alpha over the pane, not a solid slab: it stays soft in
 *  both themes, the text keeps full contrast, and a hairline of the same tone closes the
 *  edge. Horizontal padding is generous, vertical snug - the proportion that reads as a
 *  label rather than a button. */
internal class Badge(text: String, private val tone: Color) : JPanel(null) {

    private val label = JBLabel(text)

    init {
        isOpaque = false
        alignmentY = 0.5f
        label.foreground = tone
        label.font = JBFont.label()
        add(label)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val radius = height
        g.color = ColorUtil.toAlpha(tone, 26)
        g.fillRoundRect(0, 0, width, height, radius, radius)
        g.color = ColorUtil.toAlpha(tone, 60)
        g.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
    }

    override fun getPreferredSize(): Dimension {
        val text = label.preferredSize
        return Dimension(text.width + JBUI.scale(PAD_X) * 2, text.height + JBUI.scale(PAD_Y) * 2)
    }

    override fun doLayout() {
        label.setBounds(
            JBUI.scale(PAD_X),
            (height - label.preferredSize.height) / 2,
            label.preferredSize.width,
            label.preferredSize.height,
        )
    }

    private companion object {
        private const val PAD_X = 10
        private const val PAD_Y = 3
    }
}
