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

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.OptionTag
import io.xmake.run.target.findXMakeBuildProfileFor
import io.xmake.run.target.requireXMakeBuildProfileFor

/**
 * One `xmake test` run: the project's tests under the active build profile, with the
 * group and name filters the action takes. The output stays in the run console, paths
 * hyperlinked; per-test results read from the lines xmake prints as it runs them.
 */
class XMakeTestRunConfiguration(
    project: Project,
    name: String,
    factory: ConfigurationFactory,
) : RunConfigurationBase<RunProfileState>(project, factory, name) {

    /** Group and name patterns passed to `xmake test` verbatim, e.g. `test_*` or `-g quick`. */
    @OptionTag(tag = "testFilters")
    var testFilters: String = ""

    /** Prints the failing tests' output; on by default, the way CI runs read. */
    @OptionTag(tag = "outputOnFailure")
    var outputOnFailure: Boolean = true

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID) {
            throw com.intellij.execution.ExecutionException(
                "Tests run through the Run executor; other executors are not supported yet",
            )
        }
        // The profile resolves the project directory and environment the test run needs.
        project.requireXMakeBuildProfileFor(environment.executionTarget)
        return XMakeTestState(this, environment)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        XMakeTestRunConfigurationEditor()

    /** The profile targets serve the tests like they serve the runs. */
    override fun canRunOn(target: com.intellij.execution.ExecutionTarget): Boolean =
        project.findXMakeBuildProfileFor(target) != null
}

class XMakeTestRunConfigurationType : ConfigurationTypeBase(
    ID,
    "XMake Test",
    "Run the project's tests through xmake test",
    AllIcons.RunConfigurations.TestMark,
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                XMakeTestRunConfiguration(project, "XMake Test", this)
        })
    }

    companion object {
        const val ID = "XMakeTestRunConfiguration"

        fun getInstance(): XMakeTestRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(XMakeTestRunConfigurationType::class.java)
    }
}
