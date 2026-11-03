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
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.directory.xmakeProjectDirectories
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.project.toolkit.ToolkitHostType.LOCAL
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.project.toolkit.ToolkitHostType.WSL
import io.xmake.project.xmakeSettings
import io.xmake.utils.SystemUtils
import io.xmake.utils.cli.XMakeCommandLine
import io.xmake.utils.cli.DEFAULT_TARGET
import io.xmake.utils.cli.QUERY_ENVIRONMENT
import io.xmake.utils.cli.xmake
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Builds commands from one project-owned build profile captured at construction time. */
class XMakeCommandFactory(
    private val project: Project,
    private val profile: XMakeBuildProfile,
    toolkit: Toolkit? = null,
) {
    private val compileCommandsPath = project.xmakeSettings.compileCommandsPath
    val compileCommandsGeneratedPath: String = compileCommandsGeneratedPath(compileCommandsPath)
    private val compileCommandsOutputDirectory: String? = compileCommandsOutputDirectory(compileCommandsGeneratedPath)
    /** The toolkit every command from this factory runs on: the preview-selected installation,
     *  or the profile toolkit. Construction throws RuntimeConfigurationError when it is absent
     *  or unavailable. */
    val toolkit: Toolkit = toolkit ?: profile.resolveToolkit(project)
        ?.takeIf { it.isAvailable }
        ?: throw RuntimeConfigurationError(
            "XMake toolkit is not set, is unavailable in this project, or is no longer registered",
        )

    private val buildDirectoryOption = profile.buildDirectory.takeIf { it.isNotEmpty() }

    private val configureArguments: List<String> = configureArgumentsWith(profile.buildMode)

    /** xmake's mode is single-valued: [buildMode] leads the arguments so an override (e.g.
     *  `-m coverage`) replaces the profile's mode instead of being appended next to it,
     *  where xmake would silently keep only the last flag. */
    private fun configureArgumentsWith(buildMode: String): List<String> = xmake(this.toolkit).apply {
        args("-m", buildMode)
        option("-p" to profile.platform.takeUnless { it == XMakeBuildProfile.USE_XMAKE_DEFAULT })
        option("-a" to profile.architecture.takeUnless { it == XMakeBuildProfile.USE_XMAKE_DEFAULT })
        if (profile.toolchain != XMakeBuildProfile.USE_XMAKE_DEFAULT) {
            args("--toolchain=${profile.toolchain}")
        }
        if (profile.platform == ANDROID_PLATFORM && profile.androidNdkDirectory.isNotEmpty()) {
            args("--ndk=${profile.androidNdkDirectory}")
        }
        option("-o" to buildDirectoryOption)
        if (profile.configureArguments.isNotEmpty()) {
            parsedArgs(profile.configureArguments)
        }
    }.arguments

    /** `--policies=name:value,...`; empty policies add nothing. */
    private fun policyArguments(policies: Map<String, String>): List<String> =
        if (policies.isEmpty()) {
            emptyList()
        } else {
            listOf("--policies=" + policies.entries.joinToString(",") { "${it.key}:${it.value}" })
        }

    private val projectDirectory: String = project.xmakeProjectDirectories.resolveProjectDirectory(this.toolkit)
    private val configurationDirectory: String = profileConfigurationDirectory(
        profile.id,
        this.toolkit,
        projectDirectory,
        configureArguments,
    )

    fun createBuild(): XMakeCommand = createTargetBuild(DEFAULT_TARGET)

    fun createTargetBuild(targetName: String): XMakeCommand = createCommand {
        build {
            target = targetName
            verbose = profile.verbose
        }
    }

    fun createRebuild(): XMakeCommand = createCommand {
        build {
            rebuild = true
            verbose = profile.verbose
        }
    }

    fun createClean(): XMakeCommand = createCommand {
        // `clean -a` also removes the cached configuration for the profile, which a
        // subsequent configure rebuilds.
        clean {
            all = true
            verbose = profile.verbose
        }
    }

    fun createCleanConfiguration(): XMakeCommand = createCommand {
        config {
            clean = true
            yes = true
            verbose = profile.verbose
            outputDirectory = buildDirectoryOption
        }
    }

    /**
     * Configures the profile's build. [modeOverride] replaces the profile's build mode for this
     * invocation only, used by coverage runs to configure `-m coverage` or a sanitized run's
     * `-m debug`, without touching the profile the user edits. [policies] sets config-time
     * `--policies=name:value` options - the sanitizer instrumentation rides on those, not on
     * the `mode.*` rules, which only fire when the project attached them. [extraArguments]
     * appends raw config-time options verbatim - the coverage build's `--coverage` flags go
     * through here, merged by the caller so they cannot shadow the user's own flags of the
     * same options (xmake keeps the last value of a repeated option).
     */
    fun createConfigure(
        modeOverride: String? = null,
        policies: Map<String, String> = emptyMap(),
        extraArguments: List<String> = emptyList(),
    ): XMakeCommand = createCommand {
        config {
            yes = true
            arguments = configureArgumentsWith(modeOverride ?: profile.buildMode) + policyArguments(policies) + extraArguments
            verbose = profile.verbose
        }
    }

    fun createUpdateCMakeLists(): XMakeCommand = createCommand {
        project {
            kind = "cmake"
            yes = true
        }
    }

    fun createUpdateCompileCommands(): XMakeCommand = createCommand {
        project {
            kind = "compile_commands"
            lsp = "clangd"
            outputDirectory = compileCommandsOutputDirectory
        }
    }

    fun createRun(
        targetName: String,
        arguments: String,
        launchWorkingDirectory: String,
        environment: EnvironmentVariablesData,
    ): XMakeCommand {
        val resolvedLaunchWorkingDirectory = resolveLaunchWorkingDirectory(
            project,
            toolkit,
            launchWorkingDirectory,
        )
        return createCommand(
            environmentVariables = environment,
        ) {
            run {
                workingDirectory = resolvedLaunchWorkingDirectory
                target = targetName
                parsedArguments = arguments
            }
        }
    }

    fun createTargetPathQuery(targetName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            lua {
                script = scriptArgument("targetpath.lua")
                target = targetName
            }
        }

    fun createInfoQuery(queryName: String, jsonOutput: Boolean = true): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            show(queryName, json = jsonOutput)
        }

    /**
     * One `xmake check clang.tidy` pass over the whole project, in this profile's context:
     * xmake builds the compile database the checker reads and prints its findings verbatim
     * on stdout, in clang-tidy's own `file:line:col: severity: message [check]` form.
     */
    fun createClangTidyCheck(checks: String? = null): XMakeCommand = createCommand {
        check("clang.tidy")
        checks?.let { option("--checks" to it) }
    }

    /**
     * One `xmake test` pass; [filters] are the group and name patterns the action takes.
     * `--output-on-failure` only exists from xmake 3.1.2 on - older versions reject the
     * unknown flag, so the toggle silently drops there and the report section carries
     * the failing output instead.
     */
    fun createTestRun(
        filters: List<String> = emptyList(),
        outputOnFailure: Boolean = true,
    ): XMakeCommand = createCommand {
        test(
            buildList {
                addAll(filters)
                if (outputOnFailure && versionAtLeast(toolkit.version, 3, 1, 2)) {
                    add("--output-on-failure")
                }
            },
        )
    }
    private fun createCommand(
        environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT,
        environmentOverrides: Map<String, String> = emptyMap(),
        configure: XMakeCommandLine.() -> Unit,
    ): XMakeCommand {
        val line = xmake(toolkit)
            .apply(configure)
            .withWorkDirectory(projectDirectory)
            .withRedirectErrorStream(false)
        environmentVariables.configureCommandLine(line, true)
        // Must stay after configureCommandLine, which writes the user environment block.
        line.environment.putAll(mapOf(XMAKE_CONFIG_DIRECTORY_ENV to configurationDirectory) + environmentOverrides)
        return XMakeCommand(line, toolkit, projectDirectory, configurationDirectory)
    }


    /**
     * Runs a target under an external analysis tool through `xmake l runtool.lua`. The script
     * resolves the target and launches the tool inside the environment xmake runs in, which
     * keeps the executor free of host-specific launch logic; `{report}` and `{binary}` in
     * [toolArguments] are substituted on the xmake side.
     */
    fun createToolRun(
        targetName: String,
        toolArguments: List<String>,
        reportRelativePath: String,
        environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): XMakeCommand {
        val runtoolScript = scriptArgument("runtool.lua")
        return createCommand(
            environmentVariables = environmentVariables,
            // The script resolves the path on the xmake side, where the separator is always
            // forward; a Windows backslash would become part of the file name there.
            environmentOverrides = extraEnvironment + mapOf(
                RUNTOOL_REPORT_VARIABLE to reportRelativePath.replace('\\', '/'),
            ),
        ) {
            lua {
                script = runtoolScript
                target = targetName
            }
            args("--")
            args(toolArguments)
        }
    }

    /** The script is extracted to the Windows temp directory; a WSL-side xmake cannot read that
     *  path, so it is handed over in the distribution's own form (the temp drive stays reachable
     *  through the /mnt mount). */
    private fun scriptArgument(scriptName: String): String {
        val scriptPath = SystemUtils.getScriptPath(scriptName)
            ?: throw ExecutionException("The $scriptName script was not found")
        return if (toolkit.host.type == WSL) {
            toolkit.host.wslDistribution?.getWslPath(Path.of(scriptPath)) ?: scriptPath
        } else {
            scriptPath
        }
    }

    companion object {
        /** The environment variable the runtool script reads the report path from. */
        const val RUNTOOL_REPORT_VARIABLE = "XMAKE_RUNTOOL_REPORT"

        /** The environment variable the runtool script reads the collapsed-stacks path from. */
        const val RUNTOOL_COLLAPSE_VARIABLE = "XMAKE_RUNTOOL_COLLAPSED"

        private const val ANDROID_PLATFORM = "android"

        private fun compileCommandsOutputDirectory(generatedPath: String): String? =
            generatedPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

        private fun profileConfigurationDirectory(
            profileId: String,
            toolkit: Toolkit,
            workingDirectory: String,
            configureArguments: List<String>,
        ): String {
            require(XMakeBuildProfile.isValidId(profileId)) {
                "Invalid XMake build profile ID: $profileId"
            }
            return hostPath(
                toolkit.host.type,
                workingDirectory,
                ".idea/xmake/profiles/$profileId/${configurationHash(toolkit, workingDirectory, configureArguments)}",
            )
        }

        private fun hostPath(hostType: ToolkitHostType, basePath: String, relativePath: String): String =
            when (hostType) {
                LOCAL -> Path.of(basePath).resolve(relativePath).toString()
                WSL, SSH -> "${basePath.trimEnd('/')}/$relativePath"
            }

        private fun configurationHash(
            toolkit: Toolkit,
            workingDirectory: String,
            configureArguments: List<String>,
        ): String {
            val identity = buildString {
                appendField("xmake-configuration-v1")
                appendField(toolkit.host.id.canonical)
                appendField(toolkit.path)
                appendField(toolkit.version)
                appendField(workingDirectory)
                configureArguments.forEach { argument -> appendField(argument) }
            }
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(UTF_8)),
            )
        }

        private fun StringBuilder.appendField(value: String) {
            append(value.length).append(':').append(value)
        }

        /** True when [version] parses at least major.minor.patch; junk never does. */
        private fun versionAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
            val numbers = VERSION_NUMBER.findAll(version).map { it.value.toInt() }.toList()
            if (numbers.size < 3) return false
            return (numbers[0] > major) ||
                    (numbers[0] == major && numbers[1] > minor) ||
                    (numbers[0] == major && numbers[1] == minor && numbers[2] >= patch)
        }

        private val VERSION_NUMBER = Regex("\\d+")
    }
}

/** The project-scoped entry for building xmake commands outside the command context of
 *  one build profile ([withProfileCommands]). */
fun Project.xmakeCommands(profile: XMakeBuildProfile): XMakeCommandFactory =
    XMakeCommandFactory(this, profile)

/** Generated file path relative to the project directory; xmake always names the file
 *  compile_commands.json, so only the directory part of [setting] applies. */
internal fun compileCommandsGeneratedPath(setting: String): String {
    val normalized = setting.trim().replace('\\', '/').trimEnd('/')
    if (normalized.isEmpty()) return COMPILE_COMMANDS_FILE_NAME
    val directory = normalized.substringBeforeLast('/', "")
    return if (directory.isEmpty()) COMPILE_COMMANDS_FILE_NAME else "$directory/$COMPILE_COMMANDS_FILE_NAME"
}
