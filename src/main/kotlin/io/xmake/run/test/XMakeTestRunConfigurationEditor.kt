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
package io.xmake.run.test

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** The test run's own options: the filters the action takes and its failure output. */
internal class XMakeTestRunConfigurationEditor : SettingsEditor<XMakeTestRunConfiguration>() {

    private val testFilters = com.intellij.ui.components.JBTextField().apply { emptyText.text = "test_*  or  -g quick" }
    private val outputOnFailure = JBCheckBox("Print the output of failing tests")

    override fun resetEditorFrom(configuration: XMakeTestRunConfiguration) {
        testFilters.text = configuration.testFilters
        outputOnFailure.isSelected = configuration.outputOnFailure
    }

    override fun applyEditorTo(configuration: XMakeTestRunConfiguration) {
        configuration.testFilters = testFilters.text.trim()
        configuration.outputOnFailure = outputOnFailure.isSelected
    }

    override fun createEditor(): JComponent = panel {
        row("Test filters:") {
            cell(testFilters).align(AlignX.FILL)
                .comment("Group and name patterns passed to xmake test, e.g. test_* or -g quick")
        }
        row {
            cell(outputOnFailure)
        }
    }
}
