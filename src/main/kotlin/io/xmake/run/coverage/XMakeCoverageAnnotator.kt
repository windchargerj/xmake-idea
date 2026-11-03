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
import com.intellij.coverage.BaseCoverageAnnotator.DirCoverageInfo
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.SimpleCoverageAnnotator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.ProjectData
import java.io.File
import java.nio.file.Path

/**
 * Renders the line and file coverage information in the editor and the project view. The base
 * class computes the numbers from the [com.intellij.rt.coverage.data.ProjectData] that
 * [XMakeCoverageRunner] loaded and supplies the platform's coverage texts, the same ones the
 * native C++ coverage shows. The branch counters have no platform home, so they live here and
 * the coverage view reads them back through [getFileBranchCoverageString].
 *
 * The base walk asks the engine which files count into the directory statistics, through an
 * internal engine hook with a default of "none"; the legal extension point for that gate is the
 * walk itself, so the walk runs here with every file that has data counted in.
 */
class XMakeCoverageAnnotator(project: Project) : SimpleCoverageAnnotator(project) {

    private var branchCoverage: Map<String, BranchCoverage> = emptyMap()

    init {
        // The editor line highlights repaint through the painter; pulling the service in here
        // starts its subscriptions with the annotator, which every coverage flow touches first.
        project.getService(XMakeCoverageEditorPainter::class.java)
    }

    override fun fillInfoForUncoveredFile(file: Path): BaseCoverageAnnotator.FileCoverageInfo? = null

    /** Replaces the branch counters once a run's reports have been parsed. */
    internal fun setBranchCoverage(branchCoverage: Map<String, BranchCoverage>) {
        this.branchCoverage = branchCoverage
    }

    /** The branch coverage string of one source file, the way the native view renders it. Files
     *  without branch counters return null and leave the cell empty. */
    fun getFileBranchCoverageString(file: File): String? =
        branchCoverage[XMakeCoveragePaths.lookupKey(file)]?.let(::branchCoverageString)

    /** The branch coverage string of a directory: the counters of every covered file below it,
     *  so the percentage matches the files the tree shows. */
    fun getDirBranchCoverageString(directory: File): String? {
        val prefix = XMakeCoveragePaths.lookupKey(directory).trimEnd('/') + "/"
        val below = branchCoverage.filterKeys { key -> key.startsWith(prefix) }
        if (below.isEmpty()) return null
        return branchCoverageString(
            BranchCoverage(
                total = below.values.sumOf(BranchCoverage::total),
                covered = below.values.sumOf(BranchCoverage::covered),
            ),
        )
    }

    private fun branchCoverageString(coverage: BranchCoverage): String? {
        if (coverage.total == 0) return null
        return "${coverage.covered * 100 / coverage.total}% branches covered"
    }

    /**
     * The directory walk of the base class, with the file gate replaced: the base asks the
     * coverage engine which files count, and that engine hook is internal, so the walk decides
     * here instead and counts every file that has coverage data, which is every file the tree
     * can show anything for.
     *
     * Everything else mirrors the base walk. One deviation: the base remembers test directories
     * in a private field to route their numbers through the test statistics; this walk reports
     * them as ordinary directories, which matches the suites this engine builds, because they
     * never track test folders separately.
     */
    override fun collectFolderCoverage(
        dir: VirtualFile,
        dataManager: CoverageDataManager,
        annotator: CoverageAnnotatorRunner,
        projectInfo: ProjectData,
        trackTestFolders: Boolean,
        index: ProjectFileIndex,
        coverageEngine: CoverageEngine,
        visitedDirs: MutableSet<in VirtualFile>,
        normalizedFiles2Files: MutableMap<String, String>,
    ): DirCoverageInfo? {
        if (ReadAction.computeBlocking<Boolean, RuntimeException> { !index.isInContent(dir) && !index.isInLibrary(dir) }) return null
        if (visitedDirs.contains(dir)) return null
        if (!shouldCollectCoverageInsideLibraryDirs() && ReadAction.computeBlocking<Boolean, RuntimeException> { index.isInLibrary(dir) }) {
            return null
        }
        visitedDirs.add(dir)
        val dirPath = normalizeFilePath(dir.path)
        val inTestSources = ReadAction.computeBlocking<Boolean, RuntimeException> { TestSourcesFilter.isTestSources(dir, project) }
        if (!trackTestFolders && inTestSources) return null
        val children = dataManager.doInReadActionIfProjectOpen { dir.children } ?: return null
        val dirInfo = DirCoverageInfo()
        for (child in children) {
            if (child.isDirectory) {
                collectFolderCoverage(
                    child,
                    dataManager,
                    annotator,
                    projectInfo,
                    trackTestFolders,
                    index,
                    coverageEngine,
                    visitedDirs,
                    normalizedFiles2Files,
                )?.let { childInfo ->
                    dirInfo.totalFilesCount += childInfo.totalFilesCount
                    dirInfo.coveredFilesCount += childInfo.coveredFilesCount
                    dirInfo.totalLineCount += childInfo.totalLineCount
                    dirInfo.coveredLineCount += childInfo.coveredLineCount
                }
            } else {
                collectBaseFileCoverage(child, annotator, projectInfo, normalizedFiles2Files)?.let { fileInfo ->
                    dirInfo.totalLineCount += fileInfo.totalLineCount
                    dirInfo.totalFilesCount++
                    if (fileInfo.coveredLineCount > 0) {
                        dirInfo.coveredFilesCount++
                        dirInfo.coveredLineCount += fileInfo.coveredLineCount
                    }
                }
            }
        }
        if (dirInfo.totalFilesCount == 0) return null
        annotator.annotateSourceDirectory(dirPath, dirInfo)
        return dirInfo
    }
}
