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
 * @file        XMakeCommandLine.kt
 *
 */
package io.xmake.utils.cli

import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.XMakeCapability
import io.xmake.project.toolkit.XMakeVersion
import io.xmake.project.toolkit.ToolkitHost
import io.xmake.project.toolkit.ToolkitHostType.LOCAL

/** An xmake command line: the shared CLI grammar plus the xmake actions. */
@CliDsl
internal class XMakeCommandLine internal constructor(installation: CliInstallation) :
    CliCommandLine(installation) {

    private var action: String? = null

    /** Starts the one action [argument] names; a command line never carries two actions. */
    private fun beginAction(action: String, argument: String = action): XMakeCommandLine = apply {
        check(this.action == null) {
            "XMake command line already starts with '${this.action}'; add one action per command"
        }
        this.action = action
        args(argument)
    }

    /** Appends [name] as a build target; `all` becomes `-a`, blank and `default` add nothing. */
    fun target(name: String?): XMakeCommandLine = apply {
        when (name) {
            null, "", DEFAULT_TARGET -> Unit
            ALL_TARGET -> args("-a")
            else -> args(name)
        }
    }

    fun version(): XMakeCommandLine = beginAction("version", "--version")

    /** Adds `check <checker>`; the project-checking plugin action (e.g. `clang.tidy`). */
    fun check(checker: String): XMakeCommandLine = apply {
        beginAction("check")
        args(checker)
    }

    /** Adds `test`; [arguments] are the test filters and options the action takes. */
    fun test(arguments: List<String> = emptyList()): XMakeCommandLine = apply {
        beginAction("test")
        args(arguments)
    }

    /** Adds `show`; [listing] selects one `-l` listing and [json] asks for the JSON form. */
    fun show(listing: String? = null, json: Boolean = false): XMakeCommandLine = apply {
        listing?.let {
            require(INFO_QUERY_PATTERN.matches(it)) { "Invalid XMake info query: $it" }
        }
        beginAction("show")
        listing?.let { option("-l" to it) }
        flag("--json", json)
    }

    /** Adds `create` for [directory]; [language] and [kind] are the `-l` and `-t` choices. */
    fun create(
        directory: String,
        language: String? = null,
        kind: String? = null,
    ): XMakeCommandLine = apply {
        beginAction("create")
        option("-P" to directory)
        option("-l" to language)
        option("-t" to kind)
    }

    /** Adds `create --list`: lists the available project templates, generating no project. */
    fun listTemplates(): XMakeCommandLine = apply {
        beginAction("create")
        flag("--list")
    }

    fun build(configure: BuildScope.() -> Unit): XMakeCommandLine = apply {
        val options = BuildScope().apply(configure)
        beginAction("build")
        flag("-r", options.rebuild)
        flag("-y")
        flag("-v", options.verbose)
        target(options.target)
    }

    fun config(configure: ConfigScope.() -> Unit): XMakeCommandLine = apply {
        val options = ConfigScope().apply(configure)
        beginAction("config")
        flag("-c", options.clean)
        flag("-y", options.yes)
        args(options.arguments)
        flag("-v", options.verbose)
        option("-o" to options.outputDirectory)
    }

    fun clean(configure: CleanScope.() -> Unit): XMakeCommandLine = apply {
        val options = CleanScope().apply(configure)
        beginAction("clean")
        flag("-a", options.all)
        flag("-v", options.verbose)
    }

    fun run(configure: RunScope.() -> Unit): XMakeCommandLine = apply {
        val options = RunScope().apply(configure)
        beginAction("run")
        option("-w" to options.workingDirectory)
        target(options.target)
        if (options.parsedArguments.isNotEmpty()) {
            parsedArgs(options.parsedArguments)
        }
    }

    fun project(configure: ProjectScope.() -> Unit): XMakeCommandLine = apply {
        val options = ProjectScope().apply(configure)
        beginAction("project")
        option("-k" to options.kind)
        flag("-y", options.yes)
        options.lsp?.let { args("--lsp=$it") }
        options.outputDirectory?.let { args("--outputdir=$it") }
    }

    fun lua(configure: LuaScope.() -> Unit): XMakeCommandLine = apply {
        val options = LuaScope().apply(configure)
        beginAction("lua", "l")
        args(options.script)
        options.target
            ?.takeUnless { it.isBlank() || it == DEFAULT_TARGET }
            ?.let { args(it) }
        args(options.arguments)
    }

    /** Adds `service`; [ServiceScope.verb] and [ServiceScope.kind] are xmake's own long flags.
     *  A verb that takes a value renders the equals form xmake's help documents
     *  (`--add-user=root`) - one argv element, no two-element ambiguity; a missing value
     *  leaves the bare flag, which xmake then errors about visibly. */
    /** Adds `service`; [ServiceScope.verb] and [ServiceScope.kind] are xmake's own long flags.
     *  A verb that takes a value renders the equals form xmake's help documents
     *  (`--add-user=root`) - one argv element, no two-element ambiguity; a missing value
     *  leaves the bare flag, which xmake then errors about visibly. The read-only verbs
     *  are version-gated here, at the rendering, like [require]'s flags. */
    fun service(configure: ServiceScope.() -> Unit): XMakeCommandLine = apply {
        val options = ServiceScope().apply(configure)
        beginAction("service")
        val capability = when (options.verb) {
            XMakeServiceVerb.STATUS -> XMakeCapability.SERVICE_STATUS
            XMakeServiceVerb.LOGS -> XMakeCapability.SERVICE_LOGS
            else -> null
        }
        capability?.let {
            check(supports(it)) { "service --${options.verb?.flag} needs ${it.requirement}" }
        }
        val readOnly = capability != null
        options.verb?.let { verb ->
            val value = if (verb.takesValue) verb.valueFrom(options) else null
            if (value != null) {
                args("--${verb.flag}=$value")
            } else {
                flag("--${verb.flag}")
            }
        }
        options.kind?.let { flag("--${it.flag}") }
        options.session?.let { args("--session=$it") }
        flag("--all", options.all)
        options.pullPath?.let { args(listOf(it)) }
        if (readOnly) environment.putAll(QUERY_ENVIRONMENT)
    }

    /** Adds `require`; the xrepo package action: listing, info, search, and the dependency
     *  graph as read-only queries, install/upgrade/uninstall as mutations. Mutations must
     *  set [RequireScope.yes] - xmake's own prompt waits on a TTY that never answers, and
     *  the command would die at timeout. `--format=json` rides on [RequireScope.json]
     *  verbatim, which `--info` and `--depgraph` honor; the listing silently ignores it
     *  (docs/roadmap-designs/xrepo-packages.md, verified on xmake 3.1.2).
     *
     *  The version gates live here, at the rendering: a flag the toolkit's xmake rejects
     *  below its capability version fails construction with the requirement in the
     *  message - an impossible line cannot leave the DSL. Read-only asks answer with the
     *  clean query environment by construction, so no call site remembers it. */
    fun require(configure: RequireScope.() -> Unit): XMakeCommandLine = apply {
        val options = RequireScope().apply(configure)
        beginAction("require")
        flag("--list", options.list)
        flag("--info", options.info)
        flag("--search", options.search)
        flag("--uninstall", options.uninstall)
        flag("--upgrade", options.upgrade)
        flag("--depgraph", options.depgraph)
        flag("--clean", options.clean)
        flag("--fetch", options.fetch)
        gate(options.download, XMakeCapability.REQUIRE_DOWNLOAD, "--download") { flag("--download") }
        gate(options.check, XMakeCapability.REQUIRE_CHECK, "--check") { flag("--check") }
        flag("--export", options.export)
        flag("--import", options.import)
        flag("--scan", options.scan)
        flag("--shallow", options.shallow)
        flag("--build", options.buildFromSource)
        flag("--force", options.force)
        option("--packagedir" to options.packageDir)
        option("--extra" to options.extra)
        flag("--format=json", options.json)
        flag("-y", options.yes)
        args(options.packages)
        if (options.readOnly) environment.putAll(QUERY_ENVIRONMENT)
    }

    /** Adds `pack`; [configure] fills the options and the package names. The scope exists so
     *  options are structurally emitted before the names - xmake's plugin-task parser swallows
     *  everything after the first package name as another name (docs/roadmap-designs/packaging.md,
     *  F8: a trailing `-f zip` re-enables every declared format and can then fail on an
     *  undeclared tool). `--autobuild` is only emitted to turn xmake's `y` default off, which
     *  keeps the line parseable by xmake versions predating the flag. */
    fun pack(configure: PackScope.() -> Unit): XMakeCommandLine = apply {
        val options = PackScope().apply(configure)
        beginAction("pack")
        option("-f" to options.formats?.takeIf { it.isNotEmpty() })
        option("-o" to options.outputDirectory)
        options.basename?.takeIf { it.isNotEmpty() }?.let { args("--basename=$it") }
        if (!options.autoBuild) {
            args("--autobuild=n")
        }
        args(options.extraArguments)
        args(options.names)
    }

    /** The version contract, evaluated for this installation's own probe answer: the
     *  rendering refuses a flag the binary cannot parse. */
    private fun supports(capability: XMakeCapability): Boolean =
        XMakeVersion.parse(installation.version) >= capability.since

    /** Renders [render] only when [capability] holds for this installation; below the
     *  version, construction fails with the requirement - an impossible line cannot
     *  leave the DSL. */
    private fun gate(enabled: Boolean, capability: XMakeCapability, asks: String, render: () -> Unit) {
        if (!enabled) return
        check(supports(capability)) { "$asks needs ${capability.requirement}" }
        render()
    }

    internal companion object {
        private val INFO_QUERY_PATTERN = Regex("[a-z]+")
    }
}

/** The sentinel target id meaning no explicit target: builders omit the target argument. */
internal const val DEFAULT_TARGET = "default"
/** The xmake pseudo-target that names every target; [XMakeCommandLine.target] maps it to `-a`. */
internal const val ALL_TARGET = "all"

/** The `xmake service` verbs this plugin drives; the flags are xmake's own long forms,
 *  all verified on 2.8.7 except the two read-only ones (3.1.2, capability-gated). */
enum class XMakeServiceVerb(internal val flag: String, internal val takesValue: Boolean = false) {
    START("start"),
    STOP("stop"),
    CONNECT("connect"),
    DISCONNECT("disconnect"),
    RECONNECT("reconnect"),
    SYNC("sync"),
    PULL("pull"),
    RESTART("restart"),
    /** Sweeps this project's files off the daemon; the scope's [ServiceScope.all] widens it. */
    CLEAN("clean"),
    /** `--xmakesrc=<path>`: syncs the given xmake program files to the daemon. */
    XMAKE_SRC("xmakesrc", takesValue = true),
    /** Server-side: mint a fresh token. */
    GEN_TOKEN("gen-token"),
    /** Server-side: add a user; the value is the user name. */
    ADD_USER("add-user", takesValue = true),
    /** Server-side: remove a user; the value is the user name. */
    RM_USER("rm-user", takesValue = true),
    /** Read-only; xmake 3.1.2+ only - [io.xmake.project.toolkit.XMakeCapability.SERVICE_STATUS] gates the ask. */
    STATUS("status"),
    /** Read-only; xmake 3.1.2+ only - [io.xmake.project.toolkit.XMakeCapability.SERVICE_LOGS] gates the ask. */
    LOGS("logs"),
}

/** The `xmake service` kinds. The remote cache answers to xmake's `--ccache` flag, and
 *  [statusDir] is the directory name its client persists its status under (which is also
 *  the kind name the bundled probeservices.lua script reports). */
enum class XMakeServiceKind(internal val flag: String, internal val statusDir: String) {
    REMOTE_BUILD("remote", "remote_build"),
    DISTCC_BUILD("distcc", "distcc_build"),
    REMOTE_CACHE("ccache", "remote_cache"),
}

@CliDsl
internal class BuildScope internal constructor() {
    var target: String? = null
    var rebuild: Boolean = false
    var verbose: Boolean = false
}

@CliDsl
internal class ConfigScope internal constructor() {
    var clean: Boolean = false
    var yes: Boolean = false
    var arguments: List<String> = emptyList()
    var verbose: Boolean = false
    var outputDirectory: String? = null
}

@CliDsl
internal class CleanScope internal constructor() {
    var all: Boolean = false
    var verbose: Boolean = false
}

@CliDsl
internal class RunScope internal constructor() {
    var workingDirectory: String? = null
    var target: String? = null
    var parsedArguments: String = ""
}

@CliDsl
internal class ProjectScope internal constructor() {
    lateinit var kind: String
    var yes: Boolean = false
    var lsp: String? = null
    var outputDirectory: String? = null
}

@CliDsl
internal class LuaScope internal constructor() {
    lateinit var script: String
    var target: String? = null

    /** The raw arguments the script's `main(...)` receives, in order. */
    var arguments: List<String> = emptyList()
}

@CliDsl
internal class ServiceScope internal constructor() {
    var verb: XMakeServiceVerb? = null
    var kind: XMakeServiceKind? = null

    /** `--session=<name>`: connect under a named session id. */
    var session: String? = null

    /** `--pull <path>`: pull the given file or directory from the daemon. */
    var pullPath: String? = null

    /** The user `--add-user`/`--rm-user` names. */
    var user: String? = null

    /** The `--xmakesrc=<path>` program files. */
    var xmakeSrc: String? = null

    /** `--all`: the clean widens from this project to every project's files. */
    var all = false
}

/** The value a takes-value verb reads off the scope it belongs to. */
private fun XMakeServiceVerb.valueFrom(options: ServiceScope): String? = when (this) {
    XMakeServiceVerb.ADD_USER, XMakeServiceVerb.RM_USER -> options.user
    XMakeServiceVerb.XMAKE_SRC -> options.xmakeSrc
    else -> null
}

@CliDsl
internal class RequireScope internal constructor() {
    var list: Boolean = false
    var info: Boolean = false
    var search: Boolean = false
    var uninstall: Boolean = false
    var upgrade: Boolean = false
    var depgraph: Boolean = false
    var json: Boolean = false
    var clean: Boolean = false
    var fetch: Boolean = false
    var download: Boolean = false
    var check: Boolean = false
    var export: Boolean = false
    var import: Boolean = false
    var scan: Boolean = false
    /** `--shallow`: does not install the ask's dependency packages. */
    var shallow: Boolean = false
    /** `--build`: always build and install from source, never a cached artifact. */
    var buildFromSource: Boolean = false
    /** `--force`: reinstall all package dependencies. */
    var force: Boolean = false
    /** `--packagedir=<dir>`: the export/import packages directory. */
    var packageDir: String? = null
    /** `--extra="{...}"`: the extra config the ask carries. */
    var extra: String? = null

    /** True for the asks whose output the plugin parses: they answer with the clean
     *  query environment by construction. */
    val readOnly: Boolean
        get() = list || info || search || scan || depgraph || fetch
    /** Answers xmake's interactive prompt; the executor channel cannot, so mutations always set it. */
    var yes: Boolean = false
    /** The packages the action names: the search term, the info target, or the install/uninstall set. */
    var packages: List<String> = emptyList()
}

@CliDsl
internal class PackScope internal constructor() {
    /** Comma-separated `-f` filter; null or empty packs every format each xpack declares.
     *  xmake intersects this with the declarations - it cannot add an undeclared format. */
    var formats: String? = null

    /** `-o` output directory; null uses xmake's default (`<build dir>/xpack/<xpack name>`). */
    var outputDirectory: String? = null

    /** `--basename`; null uses xmake's default (the xpack name, with its `-src`/`-<version>` variants). */
    var basename: String? = null

    /** Whether xmake builds the targets before packing; xmake's own default is true. */
    var autoBuild: Boolean = true

    /** Extra passthrough arguments (e.g. `-j`), already parsed into single parameters. */
    var extraArguments: List<String> = emptyList()

    /** The xpack names to pack; empty packs every declared xpack. Always emitted last. */
    var names: List<String> = emptyList()
}

/** Starts an xmake command line for [toolkit]. */
internal fun xmake(toolkit: Toolkit): XMakeCommandLine =
    xmake(CliInstallation(toolkit.path, toolkit.host, toolkit.version))

/** Starts an xmake command line for [installation]. */
internal fun xmake(installation: CliInstallation): XMakeCommandLine = XMakeCommandLine(installation)

private val IS_WINDOWS_HOST = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private val unixSearchPaths = listOf(
    // Todo: Add more paths
    $$"${HOME}/.local/bin",
    "/usr/local/bin",
    "/usr/bin",
    "/opt/homebrew/bin"
)

/**
 * Builds the PATH search that lists every xmake installation visible on [host].
 * A local Unix host searches the user's home before the system paths.
 */
internal fun locateXMakeCommand(host: ToolkitHost): CliCommandLine =
    if (host.type != LOCAL) {
        CliCommandLine(CliInstallation("sh", host)).args("-c", REMOTE_LOCATE_SCRIPT)
    } else if (IS_WINDOWS_HOST) {
        CliCommandLine(CliInstallation("where.exe", host)).args("xmake")
    } else {
        CliCommandLine(CliInstallation("which", host)).args(whichArguments(System.getProperty("user.home")))
    }

private fun whichArguments(home: String?): List<String> =
    listOf("xmake") + unixSearchPaths.map { path ->
        val resolved = home?.let { path.replace($$"${HOME}", it) } ?: path
        "$resolved/xmake"
    }

/** WSL and SSH must expand `home` remotely, so the search runs in a shell. */
private val REMOTE_LOCATE_SCRIPT = buildString {
    appendLine("command -v xmake")
    append("for path in ")
    unixSearchPaths.forEach { path ->
        append('"').append(path.replace($$"${HOME}", $$"$HOME")).append("/xmake\" ")
    }
    appendLine($$"""; do [ -x "$path" ] && echo "$path"; done""")
    appendLine("exit 0")
}

/** Environment every informational xmake query runs with. */
internal val QUERY_ENVIRONMENT = mapOf(
    "XMAKE_SKIP_HISTORY" to "1",
    "XMAKE_ROOT" to "y",
    // xmake reads XMAKE_COLORTERM (core/base/tty.lua), not XMAKE_COLOR_TERM.
    "XMAKE_COLORTERM" to "nocolor",
)
