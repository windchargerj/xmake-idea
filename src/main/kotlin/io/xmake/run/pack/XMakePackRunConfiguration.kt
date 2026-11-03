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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.run.pack

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.annotations.OptionTag
import io.xmake.icons.XMakeIcons
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.run.target.findXMakeBuildProfileFor
import io.xmake.run.target.requireXMakeBuildProfileFor
import io.xmake.utils.info.XMakeInfoManager

/**
 * One `xmake pack` run: the project's declared xpack() packages under the active build
 * profile, optionally narrowed to one package and to the formats it declares. The output
 * stays in the run console, each `packing <path> ..` line hyperlinked to the produced
 * artifact. It exists as its own type beside the launch and test configurations because a
 * pack shares none of the launch fields and is not a way to run a target
 * (docs/roadmap-designs/packaging.md, §4).
 */
class XMakePackRunConfiguration(
    project: Project,
    name: String,
    factory: ConfigurationFactory,
) : RunConfigurationBase<RunProfileState>(project, factory, name) {

    /** The xpack name passed to `xmake pack`; empty packs every declared xpack. */
    @OptionTag(tag = "packName")
    var packName: String = ""

    /** Comma-separated `-f` filter over the formats the xpack declares; empty packs all of them. */
    @OptionTag(tag = "packFormats")
    var packFormats: String = ""

    /** `-o` output directory; empty leaves xmake's default (`<build dir>/xpack/<name>`). */
    @OptionTag(tag = "outputDirectory")
    var outputDirectory: String = ""

    /** `--basename`; empty leaves xmake's default (the xpack name, with its variants). */
    @OptionTag(tag = "basename")
    var basename: String = ""

    /** `--autobuild`: build the targets before packing, xmake's own default. */
    @OptionTag(tag = "autoBuild")
    var autoBuild: Boolean = true

    /** Passthrough arguments (`-j`, ...), parsed with [ParametersListUtil]. */
    @OptionTag(tag = "extraArguments")
    var extraArguments: String = ""

    /** The formats the configuration selects, trimmed and in field order. */
    fun selectedFormats(): List<String> =
        packFormats.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID) {
            throw ExecutionException(
                "Packing runs through the Run executor; other executors are not supported yet",
            )
        }
        // The profile resolves the project directory and environment the pack run needs.
        project.requireXMakeBuildProfileFor(environment.executionTarget)
        return XMakePackState(this, environment)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        XMakePackRunConfigurationEditor()

    /** The profile targets serve the packs like they serve the runs and the tests. */
    override fun canRunOn(target: ExecutionTarget): Boolean =
        project.findXMakeBuildProfileFor(target) != null

    override fun checkConfiguration() {
        // `-f` filters what the xpacks declare (F7), so a typo would silently pack nothing;
        // failing here keeps the mistake at edit time, where the editor cannot produce it.
        selectedFormats().forEach { format ->
            if (format !in XMakePackFormats.KNOWN) {
                throw RuntimeConfigurationError(
                    "Unknown pack format '$format'; the known formats are: " +
                            XMakePackFormats.KNOWN.joinToString(", "),
                )
            }
        }
        try {
            ParametersListUtil.parse(extraArguments)
        } catch (error: Exception) {
            throw RuntimeConfigurationError("Extra arguments do not parse: ${error.message}")
        }
        // A known version refusal fails the check without a fresh query, like the launch
        // configuration's check does.
        project.activeOrSingleXMakeBuildProfile?.resolveToolkit(project)?.let { toolkit ->
            XMakeInfoManager.getInstance(project).versionRefusal(toolkit)?.let {
                throw RuntimeConfigurationError(it.message)
            }
        }
    }
}

class XMakePackRunConfigurationType : ConfigurationTypeBase(
    ID,
    "XMake Pack",
    "Run xmake pack for the project's xpack packages",
    XMakeIcons.XMAKE,
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                XMakePackRunConfiguration(project, "XMake Pack", this)
        })
    }

    companion object {
        const val ID = "XMakePackRunConfigurationType"

        fun getInstance(): XMakePackRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(XMakePackRunConfigurationType::class.java)
    }
}
