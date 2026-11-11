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
 * @file        XMakeIcons.kt
 *
 */
package io.xmake.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object XMakeIcons {

    // logo icon
    val XMAKE = load("/icons/xmake.svg")

    /** The brand mark in its colour, theme-neutral: the run configuration type and the
     *  Services view keep the official pinwheel's colour in both themes, the way CMake's
     *  run configurations carry the coloured CMake mark. */
    val COLORED = load("/icons/xmake-color.svg")

    /** The brand mark in its brand-teal treatment: the same strokes as [XMAKE] over a
     *  10%-opacity disc. Theme-neutral - the teal reads on light and dark panels - so it
     *  carries the file identity, where a colour belongs and no theme twin is needed. */
    val TRANSLUCENT = load("/icons/xmake-translucent.svg")

    // file icon
    val FILE = TRANSLUCENT

    // Most product surfaces carry the platform's own icons (AllIcons), chosen per
    // surface at its call site: the Analysis view (a tab in the XMake window), the test
    // run type, and the Services view. Two marks stay composites:
    //   - PACKAGES: the gray brand mark with a small square at its lower right;
    //   - PACK: the package box with the platform's green run marker, the same
    //     base-plus-run composition Valgrind and coverage use.

    /** The Packages tool window: the gray brand mark with a small package square. */
    val PACKAGES = load("/icons/xmake-packages.svg")

    /** The pack run: the package box with the platform's run marker. */
    val PACK = load("/icons/xmake-pack.svg")

    // error icon
    val ERROR = AllIcons.General.Error

    // warning icon
    val WARNING = AllIcons.General.Warning

    private fun load(path: String): Icon = IconLoader.getIcon(path, XMakeIcons::class.java)

}
