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
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.nio.file.Path

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
        generateReports(dataRoot)
        return SuccessCoverageLoadingResult(GcovReportParser.parse(dataRoot))
    }

    /** `gcov -i` writes one `.gcov` report per data file, next to it. */
    private fun generateReports(root: File) {
        val gcdaFiles = root.walkTopDown().filter { it.extension == "gcda" }.toList()
        if (gcdaFiles.isEmpty()) {
            Log.info("No gcda files found under $root")
            return
        }
        for (gcda in gcdaFiles) {
            runCatching {
                ProcessBuilder(GCOV, "-i", gcda.absolutePath)
                    .directory(gcda.parentFile)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }.onFailure { error -> Log.warn("Failed to run gcov for $gcda", error) }
        }
    }

    companion object {
        const val ID = "xmake-coverage-runner"

        private val Log = logger<XMakeCoverageRunner>()
        private val GCOV = if (System.getProperty("os.name").startsWith("Windows")) "gcov.exe" else "gcov"
    }
}
