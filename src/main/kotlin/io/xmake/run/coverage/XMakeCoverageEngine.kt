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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.run.coverage

import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.xmake.run.XMakeRunConfiguration

/**
 * Coverage for XMake targets. The data comes from xmake's coverage mode (`-m coverage`, gcov
 * instrumentation); [XMakeCoverageRunner] reads the `gcda` files back through `gcov -i`.
 */
class XMakeCoverageEngine : CoverageEngine() {

    override fun getPresentableText(): String = "XMake"

    override fun isApplicableTo(configuration: RunConfigurationBase<*>): Boolean =
        configuration is XMakeRunConfiguration

    override fun createCoverageEnabledConfiguration(
        configuration: RunConfigurationBase<*>,
    ): CoverageEnabledConfiguration = XMakeCoverageEnabledConfiguration(configuration)

    override fun createEmptyCoverageSuite(runner: CoverageRunner): CoverageSuite =
        XMakeCoverageSuite(this, runner)

    /** The platform's default implementation returns null; without this the suite a coverage run
     *  reports never comes into existence. */
    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        coverageFileProvider: CoverageFileProvider,
        timestamp: Long,
    ): CoverageSuite = XMakeCoverageSuite(this, project, coverageFileProvider, runner, timestamp)

    /** The suite the platform builds from a coverage-enabled configuration. Overriding this
     *  entry is the documented extension shape: it is marked override-only, and the engine's
     *  final configuration-based creator delegates to it. */
    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
        config: com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration,
    ): CoverageSuite = createCoverageSuite(name, project, runner, fileProvider, timestamp)

    /** A project service, so every part that renders coverage numbers sees the annotator the
     *  suite-chosen renewal populated. */
    override fun getCoverageAnnotator(project: Project): CoverageAnnotator =
        project.getService(XMakeCoverageAnnotator::class.java)

    override fun createCoverageViewExtension(
        project: Project,
        bundle: CoverageSuitesBundle,
    ): CoverageViewExtension = XMakeCoverageViewExtension(
        project,
        getCoverageAnnotator(project) as XMakeCoverageAnnotator,
        bundle,
    )

    /** gcov reports are per source file, which is exactly the granularity the annotator needs. */
    override fun coverageEditorHighlightingApplicableTo(file: PsiFile): Boolean = true

    override fun getQualifiedNames(file: PsiFile): Set<String> =
        file.virtualFile?.let { virtualFile -> setOf(XMakeCoveragePaths.lookupKey(virtualFile.toNioPath())) }
            ?: emptySet()

    override fun acceptedByFilters(file: PsiFile, suite: CoverageSuitesBundle): Boolean = true
}
