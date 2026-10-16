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
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunnerData
import com.intellij.coverage.view.CoverageViewManager
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.xmake.project.console.XMakeConsole
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutionService
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File

/**
 * The CMake-aligned coverage lifecycle:
 *
 * before the run: resolve this run's data directory, wipe the data of the previous run, and
 * create the suite on the EDT with the configuration attached, exactly like the platform's
 * [com.intellij.coverage.CoverageHelper] does for Java and CLion's composer does for CMake;
 * after the run: hand the pre-built suite to [CoverageDataManager.processGatheredCoverage],
 * which activates it, renews the annotator and opens the view. The platform's suite listener
 * creates the view silently, so the view is brought to the front explicitly.
 */
internal suspend fun prepareCoverageRun(
    configuration: XMakeRunConfiguration,
    environment: ExecutionEnvironment,
    execution: XMakeExecutionService,
    console: XMakeConsole,
) {
    val project = configuration.project
    val profile = project.requireXMakeBuildProfileFor(environment.executionTarget)
    val factory = XMakeCommandFactory(project, profile)

    val dataRoot = coverageDataRoot(project, profile)
    dataRoot?.let(::cleanCoverageData)
    val toolkit = factory.createConfigure(modeOverride = COVERAGE_MODE).toolkit
    execution.execute(console, factory.createConfigure(modeOverride = COVERAGE_MODE))
    execution.execute(
        console,
        factory.createTargetBuild(configuration.runTarget),
        XMakeConsoleOptions(showConsole = false, showProblems = true),
    )

    if (dataRoot != null) {
        attachSuiteBeforeRun(configuration, dataRoot, toolkit)
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
private fun attachSuiteBeforeRun(configuration: XMakeRunConfiguration, dataRoot: File, toolkit: io.xmake.project.toolkit.Toolkit) {
    val project = configuration.project
    ApplicationManager.getApplication().invokeAndWait({
        if (project.isDisposed) return@invokeAndWait
        val enabled = CoverageEnabledConfiguration.getOrCreateIfApplicable(configuration)
        if (enabled !is XMakeCoverageEnabledConfiguration) return@invokeAndWait
        val suite = enabled.createSuiteForRun(dataRoot, toolkit)
        enabled.currentCoverageSuite = suite
        Log.info("Coverage suite attached before the run (root=${dataRoot.path})")
    })
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
internal fun coverageDataRoot(project: Project, profile: io.xmake.project.profile.XMakeBuildProfile): File? {
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
