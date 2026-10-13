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

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import io.xmake.project.console.XMakeConsole
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.XMakeExecutionService
import io.xmake.run.target.requireXMakeBuildProfileFor
import java.io.File

/**
 * Reconfigures the profile in xmake's built-in coverage mode and builds the target, so the
 * produced binary writes gcov `.gcda` data when it runs. The run itself stays on the normal
 * path; xmake already ships the coverage mode, only the invocation changes.
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

    execution.execute(console, factory.createConfigure(modeOverride = COVERAGE_MODE))
    execution.execute(
        console,
        factory.createTargetBuild(configuration.runTarget),
        XMakeConsoleOptions(showConsole = false, showProblems = true),
    )
}

/**
 * Looks for gcov data under [project]'s root after a coverage run finished, and reports the
 * result. Exposed separately so the run path can call it once the target process has exited.
 */
internal fun reportCoverageData(project: Project, console: XMakeConsole) {
    val buildRoot = project.basePath?.let(::File) ?: return
    val gcdaFiles = buildRoot.walkTopDown()
        .filter { it.extension == GCDA_EXTENSION }
        .take(MAX_DATA_FILES)
        .toList()
    if (gcdaFiles.isEmpty()) {
        console.print(
            "No coverage data (.gcda) was produced. Check that the target was built in coverage mode.",
            ConsoleViewContentType.ERROR_OUTPUT,
        )
        return
    }
    console.print("Coverage data (${gcdaFiles.size} file(s)):", ConsoleViewContentType.SYSTEM_OUTPUT)
    for (file in gcdaFiles) {
        console.print("  ${file.relativeTo(buildRoot)}", ConsoleViewContentType.SYSTEM_OUTPUT)
    }
}

private const val COVERAGE_MODE = "coverage"
private const val GCDA_EXTENSION = "gcda"
private const val MAX_DATA_FILES = 200
