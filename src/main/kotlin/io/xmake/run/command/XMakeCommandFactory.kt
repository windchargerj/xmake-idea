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
package io.xmake.run.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.xmakeSettings
import io.xmake.utils.SystemUtils

/** Builds commands from one project-owned build profile captured at construction time. */
internal class XMakeCommandFactory(project: Project, profile: XMakeBuildProfile) {
    private val profile = profile.copy()
    private val compileCommandsPath = project.xmakeSettings.state.compileCommandsPath
    private val configureOptions = commandArguments {
        args("-m", profile.mode)
        option("-p", profile.platform.takeUnless { it == DEFAULT_VALUE })
        option("-a", profile.architecture.takeUnless { it == DEFAULT_VALUE })
        if (profile.toolchain != DEFAULT_VALUE) {
            args("--toolchain=${profile.toolchain}")
        }
        if (profile.platform == "android" && profile.androidNdkDirectory.isNotEmpty()) {
            args("--ndk=${profile.androidNdkDirectory}")
        }
        option("-o", profile.buildDirectory.takeIf { it.isNotEmpty() })
        if (profile.additionalConfiguration.isNotEmpty()) {
            parsedArgs(profile.additionalConfiguration)
        }
    }
    private val commandBuilder = run {
        val toolkit = profile.resolveToolkit(project)
            ?: throw RuntimeConfigurationError(
                "XMake toolkit is not set, is unavailable in this project, or is no longer registered",
            )
        val workingDirectory = profile.resolveWorkingDirectory(project, toolkit)
        XMakeCommandBuilder.forBuildProfile(profile.id, toolkit, workingDirectory, configureOptions)
    }

    fun createBuild(): XMakeCommand = createTargetBuild(DEFAULT_VALUE)

    fun createTargetBuild(target: String): XMakeCommand = createCommand {
        args("build", "-y")
        flag("-v", profile.verbose)
        target(target)
    }

    fun createRebuild(): XMakeCommand = createCommand {
        args("build", "-r", "-y")
        flag("-v", profile.verbose)
    }

    fun createClean(): XMakeCommand = createCommand {
        args("clean")
        flag("-v", profile.verbose)
    }

    fun createCleanConfiguration(): XMakeCommand = createCommand {
        args("config", "-c", "-y")
        flag("-v", profile.verbose)
        option("-o", profile.buildDirectory.takeIf { it.isNotEmpty() })
    }

    fun createConfigure(): XMakeCommand = createCommand {
        args("config", "-y")
        args(configureOptions)
        flag("-v", profile.verbose)
    }

    fun createUpdateCmakeLists(): XMakeCommand = createCommand {
        args("project", "-k", "cmake", "-y")
    }

    fun createUpdateCompileCommands(): XMakeCommand = createCommand {
        args("project", "-k", "compile_commands", "--lsp=clangd")
        compileCommandsPath
            .takeIf { it.isNotEmpty() }
            ?.let { args(it) }
    }

    fun createRun(
        target: String,
        arguments: String,
        environment: EnvironmentVariablesData,
    ): XMakeCommand = createCommand(
        environmentVariables = environment,
    ) {
        args("run")
        target(target)
        if (arguments.isNotEmpty()) {
            parsedArgs(arguments)
        }
    }

    fun createTargetPathQuery(target: String): XMakeCommand {
        val scriptPath = SystemUtils.getScriptPath("targetpath.lua")
            ?: throw ExecutionException("The target path script was not found")
        return createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            args("l", scriptPath)
            target
                .takeUnless { it == DEFAULT_VALUE || it.isBlank() }
                ?.let { args(it) }
        }
    }

    fun createInfoQuery(name: String): XMakeCommand {
        require(INFO_QUERY_NAME.matches(name)) { "Invalid XMake info query: $name" }
        return createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            args("show", "-l", name, "--json")
        }
    }

    private fun createCommand(
        environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT,
        environmentOverrides: Map<String, String> = emptyMap(),
        parameters: MutableList<String>.() -> Unit,
    ): XMakeCommand = commandBuilder
        .parameters(commandArguments(parameters))
        .environment(environmentVariables)
        .overrideEnvironment(environmentOverrides)
        .build()

    private fun MutableList<String>.target(value: String) {
        when (value) {
            "all" -> args("-a")
            "", DEFAULT_VALUE -> Unit
            else -> args(value)
        }
    }

    private companion object {
        const val DEFAULT_VALUE = "default"

        val INFO_QUERY_NAME = Regex("[a-z]+")

        val QUERY_ENVIRONMENT = mapOf(
            "XMAKE_SKIP_HISTORY" to "1",
            "XMAKE_ROOT" to "y",
            "XMAKE_COLOR_TERM" to "nocolor",
        )

        fun commandArguments(block: MutableList<String>.() -> Unit): List<String> = buildList(block)
    }
}

private fun MutableList<String>.args(vararg values: String) {
    addAll(values)
}

private fun MutableList<String>.args(values: Iterable<String>) {
    addAll(values)
}

private fun MutableList<String>.option(name: String, value: String?) {
    if (value != null) args(name, value)
}

private fun MutableList<String>.flag(name: String, enabled: Boolean) {
    if (enabled) args(name)
}

private fun MutableList<String>.parsedArgs(commandLine: String) {
    addAll(ParametersListUtil.parse(commandLine))
}
