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
package io.xmake.run.sanitizer

import io.xmake.run.analysis.AnalysisFrame
import io.xmake.run.analysis.AnalysisIssue
import io.xmake.run.analysis.AnalysisReport
import io.xmake.run.analysis.AnalysisSeverity
import io.xmake.run.analysis.AnalysisStack
/**
 * Reads the stderr of one sanitized run and turns it into [AnalysisReport] findings.
 *
 * The four runtimes share one shape - a report header, stacks of frames under role
 * captions, and a SUMMARY line closing the report - so one state machine covers them;
 * the kinds differ in what starts a report and how the captions read:
 *
 * - AddressSanitizer and LeakSanitizer: `==pid==ERROR: AddressSanitizer: <kind> ...` with
 *   `READ of size N ...`, `freed by ... here:`, leak blocks, and a shadow-byte diagram;
 * - MemorySanitizer: `WARNING: MemorySanitizer: ...` (with or without the pid header) and
 *   origin chains ("Uninitialized value was stored to memory at");
 * - ThreadSanitizer: fenced blocks with two racing stacks and thread-creation stacks;
 * - UndefinedBehaviorSanitizer: one `file:line:col: runtime error: ...` line per finding,
 *   no stacks unless the run sets print_stacktrace.
 *
 * Program output between reports is ignored; a PTY's carriage returns and stray ANSI
 * colors are stripped, so the same parser serves terminal-emulated runs. Identical
 * reports merge: the same error type on the same top frames becomes one issue with a
 * count - the way a hundred identical overflows should read in a view: once.
 */
internal class SanitizerReportParser(private val kind: SanitizerKind) {

    private val buffer = StringBuilder()
    private val issues = mutableListOf<AnalysisIssue>()
    private val strayLines = ArrayDeque<String>()

    /** The report being collected, if a header was seen and its SUMMARY is still missing. */
    private var pending: PendingReport? = null

    /** Feeds one chunk of stderr; chunk boundaries never split a frame's meaning. */
    fun feed(text: String) {
        buffer.append(text)
        while (true) {
            val breakAt = buffer.indexOfAny(LINE_BREAKS)
            if (breakAt < 0) return
            val rawLine = buffer.substring(0, breakAt)
            buffer.delete(0, breakAt + lineBreakLength(breakAt))
            consumeLine(cleanLine(rawLine))
        }
    }

    /** Closes the stream; a report whose SUMMARY never arrived still counts. */
    fun finish(targetName: String): AnalysisReport {
        if (buffer.isNotEmpty()) {
            consumeLine(cleanLine(buffer.toString()))
            buffer.setLength(0)
        }
        pending?.let { if (it.hasContent) issues += it.toIssue() }
        pending = null
        return AnalysisReport(
            source = kind.displayName,
            targetName = targetName,
            issues = merge(issues),
            unparsed = strayLines.toList(),
        )
    }

    private fun consumeLine(line: String) {
        if (line.isEmpty()) return

        val open = pending
        if (open != null) {
            val replacement = headerOf(line) ?: leakBlockOf(line)
            when {
                line.startsWith(SUMMARY_PREFIX) -> endOpenReport()

                // The next report's header, or the next leak block, replaces a still-open
                // report (a missing SUMMARY, or LeakSanitizer's blocks under one header).
                replacement != null -> {
                    if (open.hasContent) issues += open.toIssue()
                    pending = replacement
                }

                isFrame(line) -> {
                    // The prose line seen since the last frame names the stack that follows;
                    // the runtimes' captions end freely - `here:`, `at`, `by thread T2:`.
                    captionCandidate?.let(open::openStack)
                    captionCandidate = null
                    open.currentStack().add(parseFrame(line))
                }

                // The runtime's own dedup token names the same code this report blames;
                // it groups repeats more stably than the frames it prints.
                line.startsWith(DEDUP_PREFIX) -> open.dedupToken = line.substringAfter(DEDUP_PREFIX).trim()

                // ThreadSanitizer's closing fence ends a report the SUMMARY never closed.
                line.startsWith(FENCE) -> endOpenReport()

                // Diagram rows cannot name a stack; other prose may, and rides in the
                // details either way.
                looksLikeDiagram(line) -> captionCandidate = null
                else -> {
                    open.trailer += line
                    captionCandidate = line.trim()
                }
            }
            return
        }

        val header = headerOf(line) ?: leakBlockOf(line)
        if (header != null) {
            pending = header
            return
        }
        if (!line.startsWith(FENCE) && strayLines.size < STRAY_LIMIT) strayLines += line
    }

    /** The prose line a following frame would name its stack after. */
    private var captionCandidate: String? = null

    private fun endOpenReport() {
        captionCandidate = null
        val open = pending ?: return
        if (open.hasContent) issues += open.toIssue()
        pending = null
    }

    /** LeakSanitizer's per-leak blocks are findings of their own under the group header. */
    private fun leakBlockOf(line: String): PendingReport? =
        if (LEAK_BLOCK.matches(line)) PendingReport(PendingKind.LEAK, leakTypeOf(line), line) else null

    private fun isFrame(line: String): Boolean =
        COMMON_FRAME.matches(line) || TSAN_FRAME.matches(line)

    private fun looksLikeDiagram(line: String): Boolean = DIAGRAM.containsMatchIn(line)

    /** The report a header line opens, or null for lines that open nothing. */
    private fun headerOf(line: String): PendingReport? = when {
        ERROR_HEADER.containsMatchIn(line) -> {
            val rest = line.substringAfter("ERROR: ", "")
            val sanitizer = rest.substringBefore(':').trim()
            val detail = rest.substringAfter(':').trim()
            val type = detail.substringBefore(" on ").substringBefore(" at ").trim()
                .ifEmpty { detail }
            PendingReport(
                kind = if (type.startsWith("detected memory leaks")) PendingKind.LEAK_GROUP else PendingKind.REPORT,
                type = type.ifEmpty { sanitizer },
                message = detail.ifEmpty { line },
            )
        }
        WARNING_HEADER.containsMatchIn(line) -> {
            val rest = line.substringAfter("WARNING: ", "")
            val sanitizer = rest.substringBefore(':').trim()
            val type = rest.substringAfter(':').trim().substringBefore(" (pid").trim()
            PendingReport(PendingKind.REPORT, type.ifEmpty { sanitizer }, line)
        }
        UBSAN_RUNTIME_ERROR.containsMatchIn(line) -> PendingReport(
            PendingKind.REPORT,
            type = ubsanTypeOf(line),
            message = line,
            at = ubsanLocation(line),
        )
        else -> null
    }

    private fun parseFrame(line: String): AnalysisFrame {
        COMMON_FRAME.matchEntire(line)?.let { match ->
            val (function, location) = match.destructured
            return when {
                location.isEmpty() -> AnalysisFrame(function)
                UNSYMBOLIZED.matches(location) -> AnalysisFrame(function = location)
                else -> parseFrameLocation(function, location)
            }
        }
        TSAN_FRAME.matchEntire(line)?.let { match ->
            val (function, location) = match.destructured
            return parseFrameLocation(function, location)
        }
        return AnalysisFrame(function = line.trim())
    }

    /** Splits `file:line[:col]` - the column is optional and gcc often omits it. */
    private fun parseFrameLocation(function: String, location: String): AnalysisFrame {
        val match = FRAME_LOCATION.matchEntire(location) ?: return AnalysisFrame(function)
        val (file, line, column) = match.destructured
        return AnalysisFrame(function, file, line.toIntOrNull(), column.toIntOrNull())
    }

    private fun cleanLine(line: String): String = ANSI.replace(line.trim(), "")

    private fun lineBreakLength(breakAt: Int): Int = when (buffer[breakAt]) {
        '\r' -> if (breakAt + 1 < buffer.length && buffer[breakAt + 1] == '\n') 2 else 1
        else -> 1
    }

    private fun leakTypeOf(line: String): String =
        if (line.startsWith("Direct", ignoreCase = true)) "direct leak" else "indirect leak"

    /** UBSan's message carries its own classification after `runtime error:`. */
    private fun ubsanTypeOf(line: String): String =
        line.substringAfter(RUNTIME_ERROR, "")
            .substringBefore(':')
            .trim()
            .ifEmpty { "runtime error" }

    /** UBSan reports carry their location in the line itself: `file:line:col: runtime error:`. */
    private fun ubsanLocation(line: String): AnalysisFrame? {
        val match = UBSAN_LOCATION.find(line) ?: return null
        val (file, line1, column) = match.destructured
        return AnalysisFrame("", file, line1.toIntOrNull() ?: 0, column.toIntOrNull() ?: 0)
    }

    /** Merges identical issues, keeping first-seen order and summing counts. */
    private fun merge(found: List<AnalysisIssue>): List<AnalysisIssue> {
        val merged = LinkedHashMap<String, AnalysisIssue>()
        for (issue in found) {
            val existing = merged[issue.signature]
            merged[issue.signature] =
                existing?.copy(count = existing.count + issue.count) ?: issue
        }
        return merged.values.toList()
    }

    /**
     * A report while it is being collected; [at] carries UBSan's own location for the
     * one-line reports whose stacks only follow when the run prints them.
     */
    private inner class PendingReport(
        val kind: PendingKind,
        val type: String,
        val message: String,
        val at: AnalysisFrame? = null,
    ) {
        val stacks = mutableListOf<AnalysisStack>()
        val trailer = mutableListOf<String>()
        var dedupToken: String? = null

        fun openStack(title: String) {
            stacks += AnalysisStack(title, mutableListOf())
        }

        fun currentStack(): MutableList<AnalysisFrame> {
            if (stacks.isEmpty()) openStack("stack trace")
            return stacks.last().frames as MutableList
        }

        /** Frames or a location arrived: a report worth showing. */
        val hasContent: Boolean
            get() = stacks.any { it.frames.isNotEmpty() } || at != null

        fun toIssue(): AnalysisIssue {
            val kept = stacks.filter { it.frames.isNotEmpty() }
                .ifEmpty {
                    at?.let { listOf(AnalysisStack("location", listOf(it))) } ?: emptyList()
                }
            return AnalysisIssue(
                type = type,
                message = message,
                severity = severityOf(),
                stacks = kept,
                signature = signature(kept),
            )
        }

        /** UBSan findings are warnings - the runtime keeps running; the rest are errors. */
        private fun severityOf(): AnalysisSeverity = when {
            kind == PendingKind.LEAK_GROUP || kind == PendingKind.LEAK -> AnalysisSeverity.WARNING
            this@SanitizerReportParser.kind == SanitizerKind.UNDEFINED -> AnalysisSeverity.WARNING
            else -> AnalysisSeverity.ERROR
        }

        /** Same type pointing at the same code: the runtime's dedup token when it printed
         *  one, else the first frames of the first stack. */
        private fun signature(kept: List<AnalysisStack>): String = buildString {
            append(type)
            val token = dedupToken
            if (token != null) {
                append('|').append(token)
            } else {
                kept.firstOrNull()?.frames?.take(SIGNATURE_FRAMES)?.forEach {
                    append('|').append(it.signature)
                }
            }
        }
    }

    private enum class PendingKind { REPORT, LEAK_GROUP, LEAK }

    private companion object {
        private val LINE_BREAKS = charArrayOf('\n', '\r')
        private const val SUMMARY_PREFIX = "SUMMARY: "
        private const val DEDUP_PREFIX = "DEDUP_TOKEN: "
        private const val RUNTIME_ERROR = "runtime error: "
        private const val FENCE = "===="
        private const val STRAY_LIMIT = 50
        private const val SIGNATURE_FRAMES = 3

        /** `==12345==ERROR: AddressSanitizer: ...` and LeakSanitizer's group header. */
        private val ERROR_HEADER = Regex("^==\\d+==ERROR: (Address|Leak|Memory|Thread)Sanitizer")

        /** `==12345==WARNING: MemorySanitizer: ...`; MSan omits the pid header sometimes. */
        private val WARNING_HEADER = Regex("^(==\\d+==)?WARNING: (Memory|Thread)Sanitizer")

        /** `/path/file.c:12:5: runtime error: division by zero` (clang/gcc UBSan). */
        private val UBSAN_RUNTIME_ERROR = Regex(": $RUNTIME_ERROR.+")

        /** The `file:line:col` head of a UBSan report line. */
        private val UBSAN_LOCATION = Regex("""^(\S+?):(\d+)(?::(\d+))?: runtime error: """)

        /** The common symbolized frame: `    #0 0x55e1a0 in main /src/main.cpp:13:9`;
         *  gcc's column-less form and an `at ` before the location included. */
        private val COMMON_FRAME =
            Regex("\\s*#\\d+\\s+0x[0-9a-fA-F]+\\s+in\\s+(.+?)(?:\\s+(?:at\\s+)?(\\S+:\\d+(?::\\d+)?))?$")

        /** ThreadSanitizer's own frame form: `    #0 Thread1(void*) simple_race.cc:8`,
         *  module annotation allowed: `#0 main simple_race.cc:11 (exe+0xa3b4)`. */
        private val TSAN_FRAME = Regex("""\s*#\d+\s+(.+?)\s+(\S+:\d+)(?:\s+\([^)]+\))?$""")

        /** Frame locations that failed to symbolize: `(<lib>+0x1234)` or a bare address. */
        private val UNSYMBOLIZED = Regex("^\\([^)]+\\+0x[0-9a-fA-F]+\\)$|^0x[0-9a-fA-F]+$")

        /** A frame's `file:line[:col]` location, column optional; the lazy file keeps the
         *  line out of it when a column follows. */
        private val FRAME_LOCATION = Regex("""(\S+?):(\d+)(?::(\d+))?$""")

        /** Shadow-byte diagram rows and legends; narration, not a stack caption. */
        private val DIAGRAM = Regex("^=>?0x[0-9a-fA-F]+:|^Shadow byte|^Addressable:|^[0-9a-f]{2}( |$)")

        /** LeakSanitizer's per-leak blocks. */
        private val LEAK_BLOCK = Regex("^\\s*(Direct|Indirect) leak of \\d+ byte\\(s\\).*")

        /** ANSI color sequences a PTY run may leave in the stream, with or without the
         *  escape character itself; at least one digit or separator keeps plain bracketed
         *  text intact. */
        private val ANSI = Regex("""[\u001B\u009B]?\[[0-9;]+[A-Za-z]""")
    }
}
