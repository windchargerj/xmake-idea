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
package io.xmake.run.sanitizer

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.openapi.project.Project
import io.xmake.icons.XMakeIcons
import io.xmake.run.XMakeRunConfiguration

/**
 * The sanitizer's own run configuration type: one entry per analysis purpose, the way
 * the platform splits profilers and Valgrind from the plain run - the sanitizer stops
 * being a dropdown inside the main XMake configuration, and each sanitizer
 * configuration carries its kind as a field, so separate configurations can instrument
 * different kinds at once (an ASan and a TSan config side by side).
 */
class XMakeSanitizerRunConfigurationType :
    ConfigurationTypeBase(
        "XMakeSanitizerRunConfiguration",
        "XMake Sanitizer",
        "XMake run instrumented with a sanitizer (ASan, LSan, MSan, TSan, UBSan)",
        XMakeIcons.XMAKE,
    ) {

    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): XMakeRunConfiguration =
                XMakeRunConfiguration(
                    project,
                    "XMake Sanitizer",
                    this,
                ).apply { sanitizerKind = SanitizerKind.ADDRESS.name }

            override fun getId() = "Sanitizer"
        })
    }
}