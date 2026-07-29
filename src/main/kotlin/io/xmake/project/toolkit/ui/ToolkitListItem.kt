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
 * @file        ToolkitListItem.kt
 *
 */
package io.xmake.project.toolkit.ui

import io.xmake.icons.XMakeIcons
import io.xmake.project.toolkit.Toolkit
import javax.swing.Icon

open class ToolkitListItem(
    val id: String,
    var text: String?,
    var secondaryText: String? = null,
    var tertiaryText: String? = null,
    var caption: String? = null,
    var isCaptionVisible: Boolean = false,
    var icon: Icon? = null,
) : Comparable<ToolkitListItem> {

    override fun compareTo(other: ToolkitListItem): Int = when {
        this is NoneItem && other is NoneItem -> 0
        this is NoneItem -> -1
        other is NoneItem -> 1
        else -> compareValuesBy(this, other) { it.id }
    }

    class NoneItem : ToolkitListItem(id = "", text = "None")

    open class ToolkitItem(val toolkit: Toolkit) : ToolkitListItem(
        toolkit.id,
        toolkit.path,
        toolkit.name,
        toolkit.version,
        toolkit.host.type.name,
        true,
        XMakeIcons.XMAKE
    ) {
        override fun compareTo(other: ToolkitListItem): Int = when (other) {
            is NoneItem -> 1
            is ToolkitItem -> compareValuesBy(
                this, other,
                { !it.toolkit.isRegistered },
                { it.toolkit.host.type.ordinal },
                { it.toolkit.path },
            )
            else -> super.compareTo(other)
        }

        fun asRegistered(): ToolkitItem {
            require(toolkit.isRegistered) { "Toolkit is not registered" }
            caption = "Registered"
            if (!toolkit.isValid) asInvalid()
            return this
        }

        fun asInvalid(): ToolkitItem {
            return this.apply { tertiaryText = "Invalid" }
        }

    }
}
