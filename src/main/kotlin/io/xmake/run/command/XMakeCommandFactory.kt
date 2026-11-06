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
import io.xmake.utils.cli.XMakeServiceKind
import io.xmake.utils.cli.XMakeServiceVerb
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

    /**
     * Lists every test the project defines, one `target/test<TAB>group` line each - the
     * editor's test picker reads it. Runs the enumtests.lua project script, which loads
     * the cached configuration, so it needs no target and reports tests from every
     * target in project order.
     */
    fun createTestListQuery(): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            lua {
                script = scriptArgument("enumtests.lua")
            }
        }

    /**
     * One snapshot probe of the build cache and the project's xmake services, answering
     * machine-readably between `__begin__`/`__end__` markers. Runs the probeservices.lua
     * script on the toolkit's own host - the state it reads (the persisted cache option,
     * the resolved cache program, the per-service status files) lives in the profile's
     * config directory, which on WSL/SSH toolkits is on the other host. The script header
     * documents the record grammar; the panel parses only the marked payload.
     */
    fun createBuildServicesProbe(): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            lua {
                script = scriptArgument("probeservices.lua")
            }
        }

    /**
     * Materializes one service endpoint the IDE configured into the active profile's
     * project-local client.conf, or with a blank [endpoint] clears the kind's entry -
     * always through the setservice.lua script on the toolkit's own host: the config
     * directory lives there, and the IDE must not fork xmake's client state by writing
     * it from the outside (docs/roadmap-designs/audit-services.md section 3). The script
     * also resets the kind's status.txt to xmake's own post-failed-connect shape, since
     * the persisted address wins over client.conf on the next connect.
     */
    fun createSetServiceEndpoint(kind: XMakeServiceKind, endpoint: String): XMakeCommand =
        createCommand(
            environmentOverrides = mapOf(
                SET_SERVICE_KIND_VARIABLE to kind.statusDir,
                SET_SERVICE_ENDPOINT_VARIABLE to endpoint,
            ),
        ) {
            lua {
                script = scriptArgument("setservice.lua")
            }
        }

    /**
     * Lists the project's declared xpack() packages, one `name<TAB>formats` line each - the
     * pack editor's pickers read it. Runs the xpacks.lua script, which reads
     * `project.scope("xpack")`: `xmake show -l xpacks` does not exist on the verified
     * xmake 3.1.2 (docs/roadmap-designs/packaging.md, F12), and the declarations may live in
     * included files only the interpreter resolves.
     */
    fun createXpackQuery(): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            lua {
                script = scriptArgument("xpacks.lua")
            }
        }

    /**
     * Flips the persisted `ccache` option with `xmake f --ccache=y|n`, re-running the
     * profile's configure pass with only this option changed (the rest of the config is
     * sticky). xmake owns the configuration file - the panel never writes it. Whether
     * objects rebuild afterwards depends on tool presence: with a cache program found the
     * compiler gets wrapped, so the next build rebuilds affected targets.
     */
    fun createCcacheToggle(enabled: Boolean): XMakeCommand = createCommand {
        config {
            yes = true
            arguments = configureArguments + listOf("--ccache=" + if (enabled) "y" else "n")
            verbose = profile.verbose
        }
    }

    /**
     * One `xmake service` verb, e.g. `xmake service --connect --remote`. Its
     * human-readable output (including xmake's own connect failures) belongs in the
     * visible console, and callers must re-probe afterwards: the command exit code says
     * nothing about the listener, and the connection state is persisted by the client.
     */
    fun createServiceAction(verb: XMakeServiceVerb, kind: XMakeServiceKind): XMakeCommand =
        createCommand {
            service {
                this.verb = verb
                this.kind = kind
            }
        }

    fun createInfoQuery(queryName: String, jsonOutput: Boolean = true): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            show(queryName, json = jsonOutput)
        }

    /**
     * One `xmake require --list`: the packages the project declares. Plain text by design -
     * this xmake accepts `--format=json` on the listing and silently ignores it
     * (docs/roadmap-designs/xrepo-packages.md, verified on 3.1.2) - so no json flag goes out.
     */
    fun createRequireList(): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { list = true }
        }

    /**
     * One `xmake require --info --format=json <package>`: the package's resolved data, whose
     * `references` key exists exactly when the package is installed. The JSON flag needs
     * xmake 3.1.2; older toolkits reject it, the query fails, and the caller falls back to
     * [createRequireInfoText] instead.
     */
    fun createRequireInfo(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { info = true; json = true; packages = listOf(packageName) }
        }

    /**
     * One `xmake require --info <package>`: the same answer in the plain-text form that
     * predates the JSON flag - the form every toolkit answers, 2.8.7 included. The text
     * reads as `require(name):` blocks of `-> key: value` lines.
     */
    fun createRequireInfoText(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { info = true; packages = listOf(packageName) }
        }

    /** One `xmake require --clean`: uninstalls all installed packages and clears the
     *  package caches. The confirm dialog is the user's seat before it runs. */
    fun createRequireClean(): XMakeCommand =
        createCommand {
            require { clean = true }
        }

    /** One `xmake require --fetch <package>`: fetches the package's library info from
     *  the repository without installing it. */
    fun createRequireFetch(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { fetch = true; packages = listOf(packageName) }
        }

    /** One `xmake require --download-only` companion: fetch the source archives of the
     *  named packages. */
    fun createRequireDownload(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { download = true; packages = listOf(packageName) }
        }

    /** One `xmake require --check <package>`: whether the package is supported on the
     *  current platform and toolchain. */
    fun createRequireCheck(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { check = true; packages = listOf(packageName) }
        }

    /** One `xmake require --export <package>`: exports the installed package and its
     *  dependencies to a portable archive. */
    fun createRequireExport(packageName: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { export = true; packages = listOf(packageName) }
        }

    /** One `xmake require --import`: imports previously exported packages. */
    fun createRequireImport(archivePath: String): XMakeCommand =
        createCommand {
            require { import = true; packages = listOf(archivePath) }
        }

    /** One `xmake require --search <term>`: the repository hits, plain text only. */
    fun createRequireSearch(term: String): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { search = true; packages = listOf(term) }
        }

    /** One `xmake require --depgraph --format=json`: which declared package needs which. */
    fun createRequireDepgraph(): XMakeCommand =
        createCommand(environmentOverrides = QUERY_ENVIRONMENT) {
            require { depgraph = true; json = true }
        }

    /**
     * Installs [packageName] with `xmake require -y`. The `-y` is baked in so callers cannot
     * forget it: xmake's own prompt waits on a TTY that never answers, and the command would
     * hang until the executor timeout.
     */
    fun createRequireInstall(packageName: String): XMakeCommand =
        createCommand {
            require { yes = true; packages = listOf(packageName) }
        }

    /** Upgrades every installed package with `xmake require --upgrade -y`. */
    fun createRequireUpgrade(): XMakeCommand =
        createCommand {
            require { yes = true; upgrade = true }
        }

    /** Removes [packageName] with `xmake require --uninstall -y`. */
    fun createRequireUninstall(packageName: String): XMakeCommand =
        createCommand {
            require { yes = true; uninstall = true; packages = listOf(packageName) }
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

    /**
     * One `xmake pack` pass over the project's xpack packages. [formats] filters what the
     * xpacks already declare (`-f` intersects, it cannot add - an undeclared format is
     * silently skipped by xmake, docs/roadmap-designs/packaging.md, F7); empty packs every
     * declared format. [packName] empty packs every declared xpack. [extraArguments] must be
     * pre-parsed (the user's `ParametersListUtil` fields). The PackScope emits every option
     * ahead of the names, which the pack plugin's parser requires (F8).
     */
    fun createPack(
        packName: String = "",
        formats: List<String> = emptyList(),
        outputDirectory: String = "",
        basename: String = "",
        autoBuild: Boolean = true,
        extraArguments: List<String> = emptyList(),
    ): XMakeCommand = createCommand {
        pack {
            this.formats = formats.joinToString(",").takeIf { it.isNotEmpty() }
            this.outputDirectory = outputDirectory.takeIf { it.isNotEmpty() }
            this.basename = basename.takeIf { it.isNotEmpty() }
            this.autoBuild = autoBuild
            this.extraArguments = extraArguments
            this.names = listOfNotNull(packName.trim().takeIf { it.isNotEmpty() })
        }
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

        /** The environment variable the setservice script reads the service kind's
         *  status directory name from. */
        const val SET_SERVICE_KIND_VARIABLE = "XMAKE_SERVICE_SET_KIND"

        /** The environment variable the setservice script reads the endpoint from;
         *  empty means clear the kind's project-local entry. */
        const val SET_SERVICE_ENDPOINT_VARIABLE = "XMAKE_SERVICE_SET_ENDPOINT"

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
