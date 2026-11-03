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

import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.coverage.CoverageSuite
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHost
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.utils.execute.createProcess
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Loads coverage data for the IDE by running `gcov -i` over the `gcda` files a coverage run
 * produced, then parsing the reports into [ProjectData].
 */
class XMakeCoverageRunner : CoverageRunner() {

    override fun getPresentableName(): String = "XMake coverage"

    override fun getId(): String = ID

    override fun getDataFileExtension(): String = "gcda"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is XMakeCoverageEngine

    // The Path overload is the current platform entry point; the File one is deprecated.
    override fun loadCoverageData(
        coverageDataFile: Path,
        suite: CoverageSuite?,
        errorReporter: CoverageLoadErrorReporter,
    ): CoverageLoadingResult {
        val dataRoot = suite?.coverageDataFileProvider?.let { provider ->
            File(provider.coverageDataFilePath).takeIf(File::isDirectory)
        } ?: coverageDataFile.parent?.toFile() ?: coverageDataFile.toFile()
        val workingDirectory = suite?.project?.basePath
            ?.let { basePath -> runCatching { Path.of(basePath) }.getOrNull() }
        val toolkit = (suite as? XMakeCoverageSuite)?.toolkit
        generateReports(dataRoot, toolkit, suite?.project, errorReporter)
        val parsed = GcovReportParser.parse(dataRoot, workingDirectory, toolchainPathResolver(toolkit))
        suite?.project?.getService(XMakeCoverageAnnotator::class.java)
            ?.setBranchCoverage(parsed.branchCoverage)
        return SuccessCoverageLoadingResult(parsed.projectData)
    }

    /**
     * The build recorded source paths the way its toolchain saw them. A WSL build records
     * Linux paths; the resolver maps those back to this machine's form, so the data registers
     * under the files the project actually holds.
     */
    private fun toolchainPathResolver(toolkit: Toolkit?): ((String) -> Path?)? {
        if (toolkit?.host?.type != ToolkitHostType.WSL) return null
        val distribution = toolkit.host.wslDistribution ?: return null
        return { reported -> distribution.getWindowsPath(reported)?.let(Path::of) }
    }

    /**
     * `gcov -i -b` writes one report per data file, next to it. The tool runs wherever the
     * build ran: a WSL build's data belongs to the distribution's gcov, so the command runs
     * there through the same host the build used; a local build uses the gcov on PATH. Branch
     * counters are requested so the coverage view can show its branch column, like the native
     * C++ coverage does.
     */
    private fun generateReports(
        root: File,
        toolkit: Toolkit?,
        project: com.intellij.openapi.project.Project?,
        errorReporter: CoverageLoadErrorReporter,
    ) {
        val gcdaFiles = root.walkTopDown().filter { it.extension == "gcda" }.toList()
        if (gcdaFiles.isEmpty()) {
            Log.info("No gcda files found under $root")
            return
        }
        val distribution = toolkit
            ?.takeIf { it.host.type == ToolkitHostType.WSL }
            ?.host?.wslDistribution
        if (distribution != null) {
            generateReportsInWsl(gcdaFiles, distribution, project)
            return
        }
        val gcov = resolveGcov()
        if (gcov == null) {
            Log.warn("gcov is not available; looked for it on PATH")
            errorReporter.reportError(
                "gcov was not found on PATH. Add the toolchain's bin directory to PATH so coverage " +
                    "data can be read back.",
            )
            return
        }
        for (gcda in gcdaFiles) {
            runCatching {
                val process = ProcessBuilder(gcov, "-i", "-b", gcda.absolutePath)
                    .directory(gcda.parentFile)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    Log.warn("gcov exited with $exitCode for ${gcda.absolutePath}: $output")
                }
            }.onFailure { error -> Log.warn("Failed to run gcov for $gcda", error) }
        }
    }

    /**
     * The distribution's own gcov reads the data the distribution's own compiler wrote; the
     * reports land next to the data files, where the parser finds them. A bare toolkit stands
     * in for the command's host, because the command itself already names the tool.
     */
    private fun generateReportsInWsl(
        gcdaFiles: List<File>,
        distribution: com.intellij.execution.wsl.WSLDistribution,
        project: com.intellij.openapi.project.Project?,
    ) {
        val host = ToolkitHost.wsl(distribution)
        for (gcda in gcdaFiles) {
            runCatching {
                val linuxPath = distribution.getWslPath(gcda.toPath()) ?: return@runCatching
                val linuxDirectory = distribution.getWslPath(gcda.parentFile.toPath()) ?: return@runCatching
                val command = GeneralCommandLine("gcov", "-i", "-b", linuxPath)
                // The WSL plumbing checks for cancellation while patching the command line,
                // which requires a progress indicator on the thread; the data load runs on a
                // bare listener thread, so an empty indicator stands in for it.
                val process = ProgressManager.getInstance().runProcess<Process>(
                    {
                        command.createProcess(
                            Toolkit(name = distribution.toString(), host = host, path = "gcov"),
                            project,
                            linuxDirectory,
                        )
                    },
                    EmptyProgressIndicator(),
                )
                val output = process.inputStream.bufferedReader().readText()
                val finished = runCatching { process.waitFor(60, TimeUnit.SECONDS) }.getOrDefault(false)
                val exitCode = if (finished) process.exitValue() else -1
                if (exitCode != 0) {
                    Log.warn("gcov exited with $exitCode for $linuxPath: $output")
                }
            }.onFailure { error -> Log.warn("Failed to run gcov in WSL for $gcda", error) }
        }
    }

    /** gcov ships with the toolchain; resolving it on PATH covers the installed layouts. A future
     *  step can look it up next to the compiler the build profile selected. */
    private fun resolveGcov(): String? {
        val executableName = if (SystemInfo.isWindows) "gcov.exe" else "gcov"
        return (System.getenv("PATH") ?: return null)
            .split(File.pathSeparatorChar)
            .asSequence()
            .map { directory -> File(directory, executableName) }
            .firstOrNull(File::canExecute)
            ?.absolutePath
    }

    companion object {
        const val ID = "xmake-coverage-runner"

        private val Log = logger<XMakeCoverageRunner>()
    }
}
