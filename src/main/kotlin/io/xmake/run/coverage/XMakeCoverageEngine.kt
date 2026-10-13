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

import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
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
        XMakeCoverageSuite(runner)

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = XMakeCoverageAnnotator(project)

    /** gcov reports are per source file, which is exactly the granularity the annotator needs. */
    override fun coverageEditorHighlightingApplicableTo(file: PsiFile): Boolean = true

    override fun acceptedByFilters(file: PsiFile, suite: CoverageSuitesBundle): Boolean = true
}
