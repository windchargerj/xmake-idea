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
 * @file        XMakeRunConfiguration.kt
 *
 */
package io.xmake.run

import com.intellij.execution.Executor
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Transient
import io.xmake.migration.readLegacyBuildProfile
import io.xmake.migration.removeLegacyBuildProfileFields
import io.xmake.project.profile.xmakeBuildProfiles
import io.xmake.run.state.XMakeDebugState
import io.xmake.run.state.XMakeRunState
import io.xmake.run.target.xmakeBuildProfile
import org.jdom.Element

class XMakeRunConfiguration(
    project: Project,
    name: String,
    factory: ConfigurationFactory,
) : LocatableConfigurationBase<RunProfileState>(project, factory, name),
    RunConfigurationWithSuppressedDefaultDebugAction {

    @OptionTag(tag = "target")
    var runTarget: String = "default"

    @OptionTag(tag = "arguments")
    var runArguments: String = ""

    @get:Transient
    var runEnvironment: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    @OptionTag(tag = "dapDriverPath")
    var dapDriverPath: String = ""

    @OptionTag(tag = "dapDriverAutoDetect")
    var dapDriverAutoDetect: Boolean = true

    @OptionTag(tag = "launchConfiguration")
    var launchConfiguration: String = getDefaultLaunchConfigJson()

    override fun writeExternal(element: Element) {
        super.writeExternal(element)

        removeLegacyBuildProfileFields(element)
        XmlSerializer.serializeInto(this, element)
        runEnvironment.writeExternal(element)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)

        XmlSerializer.deserializeInto(this, element)
        runEnvironment = EnvironmentVariablesData.readExternal(element)
        // Transitional fallback: the project converter performs the same import before the
        // project loads; this keeps declining the conversion dialog from losing settings.
        readLegacyBuildProfile(element, name)?.let(project.xmakeBuildProfiles::addProfile)
    }

    override fun canRunOn(target: ExecutionTarget): Boolean = project.xmakeBuildProfile(target) != null

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        XMakeRunConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        when (executor.id) {
            DefaultDebugExecutor.EXECUTOR_ID -> XMakeDebugState.create(this, environment)
            DefaultRunExecutor.EXECUTOR_ID -> XMakeRunState.create(this, environment)
            else -> throw ExecutionException("Unsupported XMake executor: ${executor.id}")
        }

    companion object {
        fun getDefaultGdbLaunchConfigJson(): String {
            return """{
    "stopOnEntry": true,
    "sourceMap": {
        "enabled": "true"
    },
    "showDisassembly": "auto",
    "setupCommands": [
        {
            "description": "Enable pretty-printing for gdb",
            "text": "-enable-pretty-printing",
            "ignoreFailures": true
        }
    ],
    "variables": {
        "showArguments": true,
        "showLocals": true,
        "showGlobals": true,
        "showStatics": true,
        "showRegisters": true
    },
    "ignoreFunctionBpoints": false,
    "stopAtConnectTime": false
}"""
        }

        fun getDefaultLldbLaunchConfigJson(): String {
            return """{
    "stopOnEntry": true,
    "sourceMap": {
        "enabled": "true"
    },
    "showDisassembly": "auto",
    "initCommands": [],
    "preRunTask": {
        "commands": []
    },
    "variables": {
        "showArguments": true,
        "showLocals": true,
        "showGlobals": true,
        "showStatics": true,
        "showRegisters": true
    },
    "ignoreFunctionBpoints": false,
    "stopAtConnectTime": false
}"""
        }

        fun getDefaultLaunchConfigJson(): String = getDefaultLldbLaunchConfigJson()
    }
}
