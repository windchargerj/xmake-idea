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
package io.xmake.icons

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Most product surfaces use the platform's `AllIcons` constants; the packages tool
 * window and the pack and sanitizer run types carry composite marks of this plugin's
 * own, so their files must exist on the classpath with their `_dark` twins. A missing
 * file fails silently at runtime (a blank stripe mark or run icon), and a missing dark
 * twin vanishes on one of the two themes.
 */
class XMakeIconsTest {

    @Test
    fun `the brand mark exists in both themes`() {
        assertNotNull("missing /icons/xmake.svg", resource("/icons/xmake.svg"))
        assertNotNull("missing /icons/xmake_dark.svg", resource("/icons/xmake_dark.svg"))
        // The theme-neutral teal treatment; it needs no _dark twin by design.
        assertNotNull("missing /icons/xmake-translucent.svg", resource("/icons/xmake-translucent.svg"))
        // The coloured brand the run configuration and the Services view carry; it stays
        // coloured in both themes, so it has no _dark twin either.
        assertNotNull("missing /icons/xmake-color.svg", resource("/icons/xmake-color.svg"))
    }

    @Test
    fun `the composite surface marks carry their light and dark files`() {
        COMPOSITE_ICONS.forEach { path ->
            assertNotNull("missing $path", resource(path))
            val dark = path.replace(".svg", "_dark.svg")
            assertNotNull("missing dark twin $dark", resource(dark))
        }
    }

    private fun resource(path: String) = XMakeIcons::class.java.getResource(path)

    private companion object {
        /** The surfaces that layer a concept glyph with the brand or the platform's run
         *  marker: the packages tool window and the pack run. */
        private val COMPOSITE_ICONS = listOf(
            "/icons/xmake-packages.svg",
            "/icons/xmake-pack.svg",
        )
    }
}
