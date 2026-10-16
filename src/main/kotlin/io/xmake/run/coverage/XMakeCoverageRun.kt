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
package io.xmake.run.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageRunnerData
import com.intellij.coverage.view.CoverageViewManager
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import io.xmake.project.console.XMakeConsole
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutor
import io.xmake.run.command.xmakeCommands
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The CMake-aligned coverage lifecycle:
 *
 * before the run: resolve this run's data directory, wipe the data of the previous run,
 * configure the build with the instrumentation below, and create the suite on the EDT with
 * the configuration attached, exactly like the platform's [com.intellij.coverage.CoverageHelper]
 * does for Java and CLion's composer does for CMake;
 * after the run: hand the pre-built suite to [CoverageDataManager.processGatheredCoverage],
 * which activates it, renews the annotator and opens the view. The platform's suite listener
 * creates the view silently, so the view is brought to the front explicitly.
 *
 * The instrumentation cannot ride the mode alone: `-m coverage` means something only when the
 * project's xmake.lua attached `add_rules("mode.coverage")` - without the rule xmake runs a
 * silent plain build whose compile line carries no `--coverage` and whose binaries leave no
 * gcov data (verified on 2.8.7 and 3.1.2, gcc). xmake's policies have no coverage entry
 * either (`xmake show -l policies`, both versions), so - unlike the sanitizer's
 * `build.sanitizer.*` policies - there is no rule-free policy route. The run therefore
 * injects the raw config-time flags (`--cxflags/--ldflags/--shflags --coverage`) whenever the
 * toolchain is a gcc/clang one; they are harmless when the rule is attached too (the flag
 * lands once, the rule's `-g -O0` stays, verified on 3.1.2). Toolchains without gcov (MSVC)
 * get the mode alone plus a notice, because a flag they ignore would only hide the problem -
 * and the build's outcome is checked for instrumented objects either way, so a run that
 * would end without data says why before the target even starts.
 */
internal suspend fun prepareCoverageRun(
    configuration: XMakeRunConfiguration,
    environment: ExecutionEnvironment,
    executor: XMakeExecutor,
    console: XMakeConsole,
) {
    val project = configuration.project
    val profile = project.requireXMakeBuildProfileFor(environment.executionTarget)
    val factory = project.xmakeCommands(profile)

    val dataRoot = coverageDataRoot(project, profile)
    dataRoot?.let(::cleanCoverageData)
    val coverageArguments = coverageConfigureArguments(profile.toolchain, profile.configureArguments)
    announceInstrumentationPlan(project, profile, coverageArguments, console)
    val configureCommand = factory.createConfigure(
        modeOverride = COVERAGE_MODE,
        extraArguments = coverageArguments,
    )
    val toolkit = configureCommand.toolkit
    executor.execute(
        console, configureCommand,
        XMakeConsoleOptions(showConsole = false, showOnFailure = true),
    )
    executor.execute(
        console,
        factory.createTargetBuild(configuration.runTarget),
        XMakeConsoleOptions(showConsole = false, showOnFailure = true, showProblems = true),
    )

    if (dataRoot != null) {
        warnWhenBuildNotInstrumented(toolkit, dataRoot, console)
        attachSuiteBeforeRun(configuration, dataRoot, toolkit)
    }
}

/** Whether [toolchain] compiles with a gcc/clang family, the only ones carrying gcov's
 *  `--coverage`. The profile's "default" defers to xmake's per-host resolution, which was
 *  gcc-family on every verified host; where it resolves to MSVC instead, the run's
 *  post-build check reports the missing instrumentation. */
internal fun toolchainAcceptsGcovFlags(toolchain: String): Boolean =
    toolchain == XMakeBuildProfile.USE_XMAKE_DEFAULT || GCOV_TOOLCHAIN_FAMILY.containsMatchIn(toolchain)

/**
 * The configure arguments instrumenting the build for gcov, merged into the user's own
 * flags. xmake keeps the last value of a repeated option - `--cxflags=-DFOO
 * --cxflags=--coverage` compiles with `--coverage` alone (verified on 3.1.2) - so an
 * existing `--cxflags=`/`--ldflags=`/`--shflags=` token gains ` --coverage` inside its own
 * value instead of being shadowed by a second token, and a value already carrying the flag
 * stays untouched. Empty when the toolchain cannot take the flags; the caller then leans on
 * the project's mode.coverage rule and says so in the console. Shared-library targets need
 * the `--shflags` twin: without it their link misses the gcov runtime and fails on
 * `__gcov_init` (verified on 3.1.2, mingw gcc).
 */
internal fun coverageConfigureArguments(toolchain: String, userConfigureArguments: String): List<String> {
    if (!toolchainAcceptsGcovFlags(toolchain)) return emptyList()
    val tokens = ParametersListUtil.parse(userConfigureArguments)
    return GCOV_FLAG_OPTIONS.mapNotNull { option ->
        val existing = tokens.lastOrNull { it.startsWith("$option=") }?.substringAfter('=')
            ?: return@mapNotNull "$option=$GCOV_FLAG"
        if (existing.split(' ').contains(GCOV_FLAG)) null else "$option=$existing $GCOV_FLAG"
    }
}

/**
 * Whether the project's root xmake.lua references the mode.coverage rule. The loose textual
 * check only words the console notice: a rule coming from an included script stays unnoticed
 * and merely repeats a hint, it never blocks the run.
 */
internal fun declaresCoverageRule(projectBasePath: String?): Boolean =
    projectBasePath?.let { root -> File(root, "xmake.lua") }
        ?.takeIf(File::isFile)
        ?.let { file -> runCatching { file.readText() }.getOrDefault("") }
        ?.contains(COVERAGE_RULE) == true

/**
 * One line ahead of the configure stating which instrumentation route this run takes, so a
 * run that ends without data traces back to its plan instead of the user guessing. The
 * routes: raw `--coverage` flags (gcc/clang, with or without the project's rule), the
 * project's rule alone (toolchains the flags cannot ride), or - the combination that
 * cannot work - neither.
 */
private fun announceInstrumentationPlan(
    project: Project,
    profile: XMakeBuildProfile,
    coverageArguments: List<String>,
    console: XMakeConsole,
) {
    val declaresRule = declaresCoverageRule(project.basePath)
    when {
        coverageArguments.isNotEmpty() && !declaresRule -> console.print(
            "No add_rules(\"mode.coverage\") in the project's xmake.lua - the coverage build " +
                    "injects --coverage into the compiler, linker, and shared-library flags directly.",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )

        coverageArguments.isEmpty() && declaresRule -> console.print(
            "The '${profile.toolchain}' toolchain takes no --coverage flags; the project's " +
                    "mode.coverage rule drives the instrumentation.",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )

        coverageArguments.isEmpty() -> console.print(
            "The '${profile.toolchain}' toolchain takes no --coverage flags and the project's xmake.lua " +
                    "attaches no add_rules(\"mode.coverage\") - this run will build without instrumentation " +
                    "and collect no coverage data. Attach the rule or pick a gcc/clang toolchain.",
            ConsoleViewContentType.ERROR_OUTPUT,
        )
    }
}

/**
 * The outcome check behind the plan: a gcc/clang compile drops one `.gcno` per translated
 * object while it works, so a build directory holding none after the build means nothing was
 * instrumented - the run would otherwise end in the platform's bare "data was not
 * registered" with no cause. The check reads files, not binary symbols: a release link with
 * `-s` strips the gcov symbols while the instrumentation stays live (verified: `nm` reports
 * no symbols yet the .gcda files still appear). A `.gcno` of an earlier instrumented
 * coverage build can mask one failed re-instrumentation; the platform's missing-data notice
 * then remains the fallback. Where the build's files cannot be seen from this machine (SSH)
 * the check stays out of the way.
 */
private fun warnWhenBuildNotInstrumented(toolkit: Toolkit, dataRoot: File, console: XMakeConsole) {
    if (toolkit.host.type == ToolkitHostType.SSH) {
        Log.info("Coverage build artifacts are not visible over SSH; skipping the .gcno check")
        return
    }
    val instrumented = runCatching { dataRoot.walkTopDown().any { it.isFile && it.extension == GCNO_EXTENSION } }
        .getOrDefault(true)
    if (!instrumented) {
        console.print(
            "The build produced no instrumented objects (.gcno) - the target will leave no coverage data. " +
                    "Attach add_rules(\"mode.coverage\") in xmake.lua or run with a gcc/clang toolchain " +
                    "(MSVC has no gcov instrumentation).",
            ConsoleViewContentType.ERROR_OUTPUT,
        )
    }
}

/** Discards the data of earlier runs, so the suite that follows reports this run only. The
 *  `.gcno` notes stay: gcov needs them to read the data back and the next build only rewrites
 *  them when it recompiles. */
private fun cleanCoverageData(root: File) {
    root.walkTopDown()
        .filter { it.isFile && (it.extension == GCDA_EXTENSION || it.name.contains(GCOV_MARKER)) }
        .forEach(File::delete)
}

/**
 * Creates the suite for this run before the target starts, mirroring the platform's
 * [com.intellij.coverage.CoverageHelper]: on the EDT, with the configuration attached, stored
 * on the coverage-enabled configuration. After the run only [CoverageDataManager.processGatheredCoverage]
 * is needed to activate it.
 */
private suspend fun attachSuiteBeforeRun(configuration: XMakeRunConfiguration, dataRoot: File, toolkit: Toolkit) {
    val project = configuration.project
    withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext
        val enabled = CoverageEnabledConfiguration.getOrCreateIfApplicable(configuration)
        if (enabled !is XMakeCoverageEnabledConfiguration) return@withContext
        val suite = enabled.createSuiteForRun(dataRoot, toolkit)
        enabled.currentCoverageSuite = suite
        Log.info("Coverage suite attached before the run (root=${dataRoot.path})")
    }
}

/**
 * Activates the suite that [attachSuiteBeforeRun] created. Called once the target process has
 * exited; this is what the platform's CMake coverage path does after its composer has run.
 */
internal fun reportCoverageData(configuration: XMakeRunConfiguration, console: XMakeConsole) {
    val project = configuration.project
    if (project.isDisposed) return

    // The two-argument overload only acts when the settings are CoverageRunnerData; it is the
    // gate the platform uses to tell coverage runs apart, and passing null makes it a no-op.
    CoverageDataManager.getInstance(project)
        .processGatheredCoverage(configuration, CoverageRunnerData())

    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val dataManager = CoverageDataManager.getInstance(project)
        val bundle = dataManager.currentSuitesBundle
        Log.info("after processGatheredCoverage: bundle present=${bundle != null}")

        // The platform's suite listener only creates the view silently (createView with
        // activate = false), so nothing brings the tool window to the front by itself.
        if (bundle != null) {
            CoverageViewManager.getInstance(project).createView(bundle, true)
            console.print("Coverage data collected; see the Coverage view.", ConsoleViewContentType.SYSTEM_OUTPUT)
        } else {
            console.print(
                "Coverage data was not registered. Check the build output and try again.",
                ConsoleViewContentType.ERROR_OUTPUT,
            )
        }
        Log.info("Coverage suite activated (bundle present=${bundle != null})")
    }
}

/** The profile's build directory when it exists, the project root otherwise. xmake writes the
 *  instrumented objects, and with them the gcda data, under this directory. */
internal fun coverageDataRoot(project: Project, profile: XMakeBuildProfile): File? {
    val basePath = project.basePath ?: return null
    val configured = profile.buildDirectory.takeIf(String::isNotBlank)?.let(::File)
    val buildDirectory = when {
        configured == null -> File(basePath, DEFAULT_BUILD_DIRECTORY)
        configured.isAbsolute -> configured
        else -> File(basePath, configured.path)
    }
    return buildDirectory.takeIf(File::isDirectory) ?: File(basePath)
}

private val Log = Logger.getInstance("io.xmake.run.coverage.XMakeCoverageRun")

private const val COVERAGE_MODE = "coverage"
private const val GCDA_EXTENSION = "gcda"
private const val GCOV_MARKER = ".gcov"
private const val DEFAULT_BUILD_DIRECTORY = "build"

/** The gcc/clang flag turning gcov instrumentation on at compile and link time. */
private const val GCOV_FLAG = "--coverage"

/** The configure options the flag must ride: C/C++ compile, binary link, shared-library link. */
private val GCOV_FLAG_OPTIONS = listOf("--cxflags", "--ldflags", "--shflags")

/** The toolchain-name families that know [GCOV_FLAG]; matched case-insensitively. */
private val GCOV_TOOLCHAIN_FAMILY = Regex("gcc|clang|mingw|llvm|gnu", RegexOption.IGNORE_CASE)

/** The build-time twin of [GCDA_EXTENSION]: one per translated object, or no instrumentation. */
private const val GCNO_EXTENSION = "gcno"

/** The rule text a project's xmake.lua carries when the coverage mode has a meaning. */
private const val COVERAGE_RULE = "mode.coverage"
