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
package io.xmake.run.tidy

import io.xmake.run.analysis.AnalysisFrame
import io.xmake.run.analysis.AnalysisIssue
import io.xmake.run.analysis.AnalysisReport
import io.xmake.run.analysis.AnalysisSeverity
import io.xmake.run.analysis.AnalysisStack

/**
 * Reads the stdout of `xmake check clang.tidy` and turns it into findings for the analysis
 * view. clang-tidy prints one line per finding - `file:line:col: severity: message [check]`
 * - with `note` lines pointing at related locations; identical findings merge with a count.
 * Anything the checker could not classify (xmake's own progress, tool errors) is kept as
 * the report's unparsed lines so nothing disappears.
 */
internal class ClangTidyReportParser {

    private val buffer = StringBuilder()
    private val issues = mutableListOf<AnalysisIssue>()
    private val strayLines = ArrayDeque<String>()

    fun feed(text: String) {
        buffer.append(text)
        while (true) {
            val breakAt = buffer.indexOfAny(LINE_BREAKS)
            if (breakAt < 0) return
            val rawLine = buffer.substring(0, breakAt)
            buffer.delete(0, breakAt + lineBreakLength(breakAt))
            consumeLine(rawLine.trim())
        }
    }

    fun finish(projectName: String): AnalysisReport {
        if (buffer.isNotEmpty()) {
            consumeLine(buffer.toString().trim())
            buffer.setLength(0)
        }
        return AnalysisReport(
            source = "clang-tidy",
            targetName = projectName,
            issues = merge(issues),
            unparsed = strayLines.toList(),
        )
    }

    private fun consumeLine(line: String) {
        if (line.isEmpty()) return
        val match = FINDING.matchEntire(line) ?: run {
            if (strayLines.size < STRAY_LIMIT) strayLines += line
            return
        }
        val (file, line1, column, severity, message, check) = match.destructured
        issues += AnalysisIssue(
            type = check.ifEmpty { severity },
            message = message,
            severity = when (severity) {
                "error", "fatal error" -> AnalysisSeverity.ERROR
                "warning" -> AnalysisSeverity.WARNING
                else -> AnalysisSeverity.NOTE
            },
            stacks = listOf(
                AnalysisStack("location", listOf(AnalysisFrame("", file, line1.toIntOrNull(), column.toIntOrNull()))),
            ),
            signature = "$file:$line1:$column:${check.ifEmpty { severity }}:$message",
        )
    }

    /** Merges identical findings, keeping first-seen order and summing counts. */
    private fun merge(found: List<AnalysisIssue>): List<AnalysisIssue> {
        val merged = LinkedHashMap<String, AnalysisIssue>()
        for (issue in found) {
            val existing = merged[issue.signature]
            merged[issue.signature] = existing?.copy(count = existing.count + issue.count) ?: issue
        }
        return merged.values.toList()
    }

    private fun lineBreakLength(breakAt: Int): Int = when (buffer[breakAt]) {
        '\r' -> if (breakAt + 1 < buffer.length && buffer[breakAt + 1] == '\n') 2 else 1
        else -> 1
    }

    private companion object {
        private val LINE_BREAKS = charArrayOf('\n', '\r')
        private const val STRAY_LIMIT = 50

        /** `src/main.cpp:13:9: warning: use nullptr [modernize-use-nullptr]`; the check
         *  name in brackets is optional, and so is the column in older releases. */
        private val FINDING = Regex("""(\S+?):(\d+):(?:(\d+):)?\s*(fatal error|error|warning|note|remark):\s*(.+?)(?:\s+\[([^\]]+)])?$""")
    }
}
