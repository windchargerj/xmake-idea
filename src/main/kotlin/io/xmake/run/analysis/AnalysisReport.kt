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
package io.xmake.run.analysis

import io.xmake.project.toolkit.Toolkit
import java.time.Instant

/** One stack frame of a sanitizer report, as the runtime symbolized it. */
data class AnalysisFrame(
    /** The symbolized function name, or the raw address when symbolization failed. */
    val function: String,
    /** The source file the frame points into, in host form; null when unsymbolized. */
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    /** A stable, human-comparable identity for deduplicating identical frames. */
    val signature: String get() = "$function@${file ?: "?"}:${line ?: 0}"
}

/**
 * One stack of a report, with the role the runtime gave it: the faulting access for
 * AddressSanitizer, the racing read and the previous write for ThreadSanitizer, the
 * store that created an uninitialized value for MemorySanitizer.
 */
data class AnalysisStack(
    /** What the runtime called this stack: "Read of size 4", "Previous write of size 4",
     *  "allocated by thread T0 here", or the plain "stack trace" of a one-stack report. */
    val title: String,
    val frames: List<AnalysisFrame>,
)

/** How serious a finding is: errors corrupt memory or data, notes are advisory. */
enum class AnalysisSeverity {
    ERROR,
    WARNING,
    NOTE,
}

/**
 * One sanitizer finding, parsed from the run's stderr: the runtime's type and message,
 * every stack it recorded, and - after merging - how many identical findings occurred.
 */
data class AnalysisIssue(
    /** The runtime's classification: "heap-buffer-overflow", "data race", "use-of-uninitialized-value", ... */
    val type: String,
    /** The first line of the report without the pid header; the caption the view shows. */
    val message: String,
    val severity: AnalysisSeverity,
    val stacks: List<AnalysisStack>,
    /** Identical reports merge on this key: same type pointing at the same code. */
    val signature: String,
    /** How many times this report occurred; identical findings merge into one issue. */
    val count: Int = 1,
) {
    /** The first frame that names a source file, the location the finding is reported at. */
    val primaryFrame: AnalysisFrame?
        get() = stacks.firstOrNull()?.frames?.firstOrNull { it.file != null }
}

/**
 * The findings of one analysis run, ready for the analysis view. The sanitizers and
 * clang-tidy both produce one; [source] names the run that found them.
 */
data class AnalysisReport(
    /** What produced the findings: the sanitizer's or clang-tidy's display name. */
    val source: String,
    /** The target the findings came from, as the run configuration named it. */
    val targetName: String,
    val issues: List<AnalysisIssue>,
    /** The toolkit the run used; maps the frames' host paths into local ones. */
    val toolkit: Toolkit? = null,
    /** The host-side working directory the run started in, for that mapping. */
    val hostWorkingDirectory: String? = null,
    /** The stderr lines no parser consumed; shown verbatim so nothing is lost. */
    val unparsed: List<String> = emptyList(),
    /** Why the producing run failed, when it did - the summary names it, so a check that
     *  died partway never reads as a clean one that found nothing. */
    val failure: String? = null,
    val timestamp: Instant = Instant.now(),
) {
    val totalFindings: Int get() = issues.sumOf(AnalysisIssue::count)

    val errorCount: Int get() = issues.count { it.severity == AnalysisSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == AnalysisSeverity.WARNING }
}
