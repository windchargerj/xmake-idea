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

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.openapi.project.Project
import io.xmake.project.toolkit.Toolkit

/**
 * One coverage run's result. The data file provider points at the directory holding the gcov
 * reports that [XMakeCoverageRunner] produced.
 *
 * The engine is carried explicitly: the platform keys its bundles and active-suite table by
 * engine identity and rejects a bundle whose suites disagree on it, so the suite has to return
 * the registered engine instance rather than a fresh one.
 */
class XMakeCoverageSuite(
    private val engine: XMakeCoverageEngine,
    project: Project?,
    fileProvider: CoverageFileProvider?,
    runner: CoverageRunner,
    timestamp: Long,
    /** The toolkit the covered build ran with; its host knows where gcov must run and how the
     *  tool's paths map back to this machine. Null for suites the platform creates itself. */
    val toolkit: Toolkit? = null,
) : BaseCoverageSuite(NAME, project, runner, fileProvider, timestamp) {

    constructor(engine: XMakeCoverageEngine, runner: CoverageRunner) : this(engine, null, null, runner, -1)

    override fun getCoverageEngine(): CoverageEngine = engine

    /**
     * The provider points at the build directory, and every coverage run rewrites the data in
     * it. The platform's default would ask whether to delete that file and then delete it
     * recursively, which must not run against a build directory; the question is also
     * EDT-bound, and suite creation happens on the process-termination thread.
     */
    override fun deleteCachedCoverageData() = Unit

    private companion object {
        const val NAME = "XMake Coverage"
    }
}
