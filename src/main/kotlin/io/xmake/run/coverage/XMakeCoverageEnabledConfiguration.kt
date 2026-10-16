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
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageSuite
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import java.io.File

/**
 * Coverage options attached to one XMake run configuration. The coverage run points the suite's
 * provider at the build directory; this fallback keeps the project root, which still contains
 * the gcov data.
 */
// The base-class constructors are deprecated but remain the platform's inheritance point for
// custom coverage engines; there is no replacement without dropping the engine entirely.
@Suppress("DEPRECATION")
class XMakeCoverageEnabledConfiguration(
    configuration: RunConfigurationBase<*>,
) : CoverageEnabledConfiguration(configuration) {

    override fun createFileProvider(): CoverageFileProvider =
        object : CoverageFileProvider {
            override fun getCoverageDataFilePath(): String = coverageFilePath

            override fun ensureFileExists(): Boolean = java.io.File(coverageFilePath).let { file ->
                file.exists() || file.mkdirs()
            }

            override fun isValid(): Boolean = java.io.File(coverageFilePath).exists()
        }

    override fun getCoverageFilePath(): String =
        configuration.project.basePath ?: super.getCoverageFilePath() ?: ""

    /**
     * The pre-run suite creation the platform's coverage flow expects: a suite whose provider
     * points at this run's data directory, carrying the toolkit the run uses so the loader can
     * process the data where the build produced it. The engine hands back the registered
     * instance so the platform's engine-identity checks hold.
     */
    fun createSuiteForRun(dataRoot: File, toolkit: io.xmake.project.toolkit.Toolkit?): CoverageSuite {
        val engine = CoverageEngine.EP_NAME.extensionList.filterIsInstance<XMakeCoverageEngine>().firstOrNull()
            ?: XMakeCoverageEngine()
        return XMakeCoverageSuite(
            engine,
            configuration.project,
            directoryProvider(dataRoot),
            XMakeCoverageRunner(),
            System.currentTimeMillis(),
            toolkit,
        )
    }

    private fun directoryProvider(root: File): CoverageFileProvider = object : CoverageFileProvider {
        override fun getCoverageDataFilePath(): String = root.path

        override fun ensureFileExists(): Boolean = root.isDirectory

        override fun isValid(): Boolean = root.isDirectory
    }
}
