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

import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import java.io.File

/**
 * Parses gcov's intermediate text format (`gcov -i`) into the platform's [ProjectData], which
 * the coverage view and the editor annotator read. Files are registered as top-level entries
 * keyed by their path, which is how the C/C++ annotator resolves them back to source.
 */
internal object GcovReportParser {

    private val FILE_LINE = Regex("^file:(.+)$")
    private val LINE_LINE = Regex("^line:([0-9]+),([0-9]+)(,.*)?$")

    /** Parses every `gcov -i` report under [root] (or the file itself) into one [ProjectData]. */
    fun parse(root: File): ProjectData {
        val projectData = ProjectData()
        val reports = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown().filter { it.extension == GCOV_EXTENSION }
        }
        for (report in reports) {
            runCatching { parseReport(report, projectData) }
                .onFailure { /* A malformed report must not abort the remaining ones. */ }
        }
        return projectData
    }

    private fun parseReport(report: File, projectData: ProjectData) {
        var currentClass: ClassData? = null
        val hitsByLine = mutableMapOf<Int, Int>()
        var maxLine = 0

        // A report describes one source file: a `file:` header followed by `line:` records.
        for (rawLine in report.readLines()) {
            val line = rawLine.trim()
            FILE_LINE.find(line)?.let { match ->
                flush(projectData, currentClass, hitsByLine, maxLine)
                hitsByLine.clear()
                maxLine = 0
                currentClass = projectData.getOrCreateClassData(normalizePath(match.groupValues[1]))
                return@let
            }
            LINE_LINE.find(line)?.let { match ->
                val lineNumber = match.groupValues[1].toIntOrNull() ?: return@let
                val hits = match.groupValues[2].toIntOrNull() ?: return@let
                hitsByLine[lineNumber] = (hitsByLine[lineNumber] ?: 0) + hits
                if (lineNumber > maxLine) maxLine = lineNumber
            }
        }
        flush(projectData, currentClass, hitsByLine, maxLine)
    }

    /** The platform reads [LineData] arrays by index, so lines are sorted ascending. */
    private fun flush(
        projectData: ProjectData,
        classData: ClassData?,
        hitsByLine: Map<Int, Int>,
        maxLine: Int,
    ) {
        val target = classData ?: return
        if (hitsByLine.isEmpty()) return
        val lines = hitsByLine.entries.sortedBy { it.key }.map { (lineNumber, hits) ->
            LineData(lineNumber, null).apply { repeat(hits) { touch() } }
        }
        target.setLines(lines.toTypedArray())
    }

    /** gcov reports Windows paths with forward slashes; the IDE compares canonical paths. */
    private fun normalizePath(path: String): String = path.replace('\\', File.separatorChar)

    private const val GCOV_EXTENSION = "gcov"
}
