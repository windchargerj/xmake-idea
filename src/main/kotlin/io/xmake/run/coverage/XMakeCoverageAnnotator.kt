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

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Renders the line and file coverage information in the editor and the project view. The base
 * class computes the numbers from the [com.intellij.rt.coverage.data.ProjectData] that
 * [XMakeCoverageRunner] loaded; only the labels are supplied here.
 */
class XMakeCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project) {

    override fun fillInfoForUncoveredFile(file: Path): BaseCoverageAnnotator.FileCoverageInfo? = null

    override fun getLinesCoverageInformationString(fileInfo: BaseCoverageAnnotator.FileCoverageInfo): String {
        val total = fileInfo.totalLineCount
        if (total == 0) return "no lines"
        return "${fileInfo.coveredLineCount}/$total lines covered"
    }

    override fun getFilesCoverageInformationString(dirInfo: BaseCoverageAnnotator.DirCoverageInfo): String {
        val total = dirInfo.totalFilesCount
        if (total == 0) return "no files"
        return "${dirInfo.coveredFilesCount}/$total files covered"
    }
}
