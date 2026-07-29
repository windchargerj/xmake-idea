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
package io.xmake.project.profile.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.MutableCollectionComboBoxModel

internal class XMakeProfileOptionComboBox : ComboBox<String>(MutableCollectionComboBoxModel()) {
    private val optionModel: MutableCollectionComboBoxModel<String>
        get() = model as MutableCollectionComboBoxModel<String>

    init {
        isSwingPopup = true
        maximumRowCount = 30
    }

    fun updateOptions(
        values: Iterable<String>,
        selected: Any?,
        fallback: String = DEFAULT_VALUE,
    ) {
        val selectedValue = selected?.toString().orEmpty().ifBlank { fallback }
        val options = buildList {
            add(fallback)
            addAll(values.filter(String::isNotBlank))
            add(selectedValue)
        }.distinct()

        updateModel(options)
        if (selectedItem != selectedValue) selectedItem = selectedValue
    }

    private fun updateModel(options: List<String>) {
        val commonSize = minOf(optionModel.size, options.size)
        for (index in 0 until commonSize) {
            if (optionModel.getElementAt(index) != options[index]) {
                optionModel.setElementAt(options[index], index)
            }
        }
        when {
            optionModel.size > options.size ->
                optionModel.removeRange(options.size, optionModel.size - 1)
            optionModel.size < options.size ->
                optionModel.add(options.drop(optionModel.size))
        }
    }

    private companion object {
        const val DEFAULT_VALUE = "default"
    }
}
