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

import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.coverage.view.ElementColumnInfo
import com.intellij.coverage.view.PercentageCoverageColumnInfo
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ColumnInfo
import java.io.File

/**
 * The directory tree the Coverage tool window shows, column for column the shape of CLion's
 * C++ coverage view: the element, the line percentages from the annotator, and the branch
 * percentages computed from the counters the loader collected. The base class walks the
 * project directories, lists the files the annotator has data for and renders the rows.
 */
class XMakeCoverageViewExtension(
    project: Project,
    private val annotator: XMakeCoverageAnnotator,
    private val bundle: CoverageSuitesBundle,
) : DirectoryCoverageViewExtension(project, annotator, bundle) {

    override fun createColumnInfos(): Array<ColumnInfo<NodeDescriptor<*>, String>> = arrayOf(
        ElementColumnInfo(),
        PercentageCoverageColumnInfo(LINE_COLUMN_INDEX, LINE_COVERAGE_COLUMN_NAME, bundle),
        PercentageCoverageColumnInfo(BRANCH_COLUMN_INDEX, BRANCH_COVERAGE_COLUMN_NAME, bundle),
    )

    /** The third column reads the branch counters of the file or, for a directory, of every
     *  covered file below it; the other columns keep the platform behavior. */
    override fun getPercentage(columnIdx: Int, node: AbstractTreeNode<*>): String? {
        if (columnIdx != BRANCH_COLUMN_INDEX) return super.getPercentage(columnIdx, node)
        val file = extractFile(node) ?: return null
        return if (file.isDirectory) {
            annotator.getDirBranchCoverageString(File(file.path))
        } else {
            annotator.getFileBranchCoverageString(File(file.path))
        }
    }

    companion object {
        private const val LINE_COLUMN_INDEX = 1
        private const val BRANCH_COLUMN_INDEX = 2
        private const val LINE_COVERAGE_COLUMN_NAME = "Line Coverage, %"
        private const val BRANCH_COVERAGE_COLUMN_NAME = "Branch Coverage, %"
    }
}
