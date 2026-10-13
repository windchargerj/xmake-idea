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

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project

/**
 * One coverage run's result. The data file provider points at the directory holding the gcov
 * reports that [XMakeCoverageRunner] produced.
 */
class XMakeCoverageSuite : BaseCoverageSuite {

    constructor(runner: CoverageRunner) : super(NAME, null, runner, null, -1)

    constructor(
        project: Project,
        fileProvider: CoverageFileProvider,
        runner: CoverageRunner,
        timestamp: Long,
    ) : super(NAME, project, runner, fileProvider, timestamp)

    constructor(configuration: CoverageEnabledConfiguration) : super(
        NAME,
        configuration.configuration.project,
        configuration.coverageRunner,
        null,
        -1,
    )

    override fun getCoverageEngine(): CoverageEngine = XMakeCoverageEngine()

    private companion object {
        const val NAME = "XMake Coverage"
    }
}
