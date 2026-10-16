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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** What one parse yields: the line data the platform annotates with, and the branch counters
 *  the coverage view renders in its branch column. */
internal class ParsedCoverage(
    val projectData: ProjectData,
    val branchCoverage: Map<String, BranchCoverage>,
)

/** The branch counters of one source file: how many branches exist and how many ran at least
 *  once, the two numbers the branch coverage percentage is computed from. */
internal data class BranchCoverage(val total: Int, val covered: Int)

/**
 * Parses gcov reports into the platform's [ProjectData], keyed by the path of the source file,
 * plus the branch counters the coverage view shows in its own column.
 *
 * `gcov -i -b` writes two shapes of reports: the text format up to GCC 8 (`lcount:` records in
 * `.gcov` files) and the JSON format of GCC 9 and later (`.gcov.json.gz`, uncompressed
 * `.gcov.json` since GCC 12). Both carry the per-line execution counts of every source file a
 * translation unit touched, headers included; branch counters exist in the JSON format only,
 * which is why CLion's branch view asks for GCC 12 or newer.
 */
internal object GcovReportParser {

    private val FILE_HEADER = Regex("^file:(.+)$")
    private val LCOUNT_RECORD = Regex("^lcount:(\\d+),(\\d+)")

    /** Parses every report under [root] (or the file itself) into one [ProjectData]. Relative
     *  paths are resolved against [workingDirectory], the directory the build ran in, because
     *  gcov records source paths the way the compiler saw them. [toolchainPathResolver] maps a
     *  reported absolute path when the build's toolchain saw a different filesystem than this
     *  IDE, a WSL build for example, whose `/mnt` paths map onto Windows drives. */
    fun parse(root: File, workingDirectory: Path?): ParsedCoverage = parse(root, workingDirectory, null)

    fun parse(
        root: File,
        workingDirectory: Path?,
        toolchainPathResolver: ((String) -> Path?)?,
    ): ParsedCoverage {
        val projectData = ProjectData()
        val branchCoverage = mutableMapOf<String, BranchCoverage>()
        val reports = if (root.isFile) sequenceOf(root) else root.walkTopDown().filter(::isReport)
        for (report in reports) {
            runCatching {
                when {
                    report.name.endsWith(JSON_GZ_SUFFIX) -> GZIPInputStream(report.inputStream()).use { stream ->
                        parseJson(stream, workingDirectory, projectData, branchCoverage, toolchainPathResolver)
                    }

                    report.name.endsWith(JSON_SUFFIX) -> report.inputStream().use { stream ->
                        parseJson(stream, workingDirectory, projectData, branchCoverage, toolchainPathResolver)
                    }

                    else -> parseText(report, workingDirectory, projectData, toolchainPathResolver)
                }
            }.onFailure { error -> Log.warn("Failed to parse the gcov report ${report.absolutePath}", error) }
        }
        return ParsedCoverage(projectData, branchCoverage)
    }

    private fun isReport(file: File): Boolean = file.isFile && (
        file.name.endsWith(TEXT_SUFFIX) || file.name.endsWith(JSON_SUFFIX) || file.name.endsWith(JSON_GZ_SUFFIX)
        )

    /** The text intermediate format: a `file:` header selects the source file, `lcount:` records
     *  follow, one per executed or executable line. */
    private fun parseText(
        report: File,
        workingDirectory: Path?,
        projectData: ProjectData,
        toolchainPathResolver: ((String) -> Path?)? = null,
    ) {
        var reportedPath: String? = null
        var hits = mutableMapOf<Int, Int>()
        for (rawLine in report.readLines()) {
            val line = rawLine.trim()
            val header = FILE_HEADER.find(line)
            if (header != null) {
                reportedPath?.let { path ->
                    lookupKey(path, workingDirectory, toolchainPathResolver)?.let { key ->
                        register(projectData, key, hits)
                    }
                }
                reportedPath = header.groupValues[1]
                hits = mutableMapOf()
                continue
            }
            val record = LCOUNT_RECORD.find(line) ?: continue
            val lineNumber = record.groupValues[1].toIntOrNull() ?: continue
            val count = record.groupValues[2].toIntOrNull() ?: continue
            hits[lineNumber] = (hits[lineNumber] ?: 0) + count
        }
        reportedPath?.let { path ->
            lookupKey(path, workingDirectory, toolchainPathResolver)?.let { key ->
                register(projectData, key, hits)
            }
        }
    }

    /** The JSON format: a `files` array whose entries carry their own `lines` array, each line
     *  optionally listing its branches. */
    private fun parseJson(
        stream: InputStream,
        workingDirectory: Path?,
        projectData: ProjectData,
        branchCoverage: MutableMap<String, BranchCoverage>,
        toolchainPathResolver: ((String) -> Path?)? = null,
    ) {
        val root = JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
        val reportWorkingDirectory = root.stringOrNull("current_working_directory")
            ?.let { directory ->
                // The report's working directory has the toolchain's form too: a WSL gcov
                // records /mnt paths, which this JVM would read as a drive-relative path.
                toolchainPathResolver
                    ?.takeIf { directory.startsWith("/") }
                    ?.invoke(directory)
                    ?: runCatching { Path.of(directory) }.getOrNull()
            }
            ?: workingDirectory
        for (element in root.arrayOrNull("files") ?: return) {
            val file = element.asJsonObject
            val reportedPath = file.stringOrNull("file") ?: continue
            val hits = mutableMapOf<Int, Int>()
            var totalBranches = 0
            var coveredBranches = 0
            for (lineElement in file.arrayOrNull("lines") ?: continue) {
                val line = lineElement.asJsonObject
                val lineNumber = line.intOrNull("line_number") ?: continue
                val count = line.intOrNull("count") ?: continue
                hits[lineNumber] = (hits[lineNumber] ?: 0) + count
                for (branchElement in line.arrayOrNull("branches") ?: continue) {
                    val branch = branchElement.asJsonObject
                    totalBranches++
                    if ((branch.intOrNull("count") ?: 0) > 0) {
                        coveredBranches++
                    }
                }
            }
            val key = lookupKey(reportedPath, reportWorkingDirectory, toolchainPathResolver)
            if (key != null) {
                register(projectData, key, hits)
                registerBranches(key, totalBranches, coveredBranches, branchCoverage)
            }
        }
    }

    /** The key a reported path registers under: the toolchain's own form of the path when the
     *  build saw another filesystem, otherwise the reported path resolved against the working
     *  directory, the way a local build records it. */
    private fun lookupKey(
        reportedPath: String,
        workingDirectory: Path?,
        toolchainPathResolver: ((String) -> Path?)?,
    ): String? {
        toolchainPathResolver
            ?.takeIf { reportedPath.startsWith('/') }
            ?.invoke(reportedPath)
            ?.let { return XMakeCoveragePaths.lookupKey(it) }
        return XMakeCoveragePaths.lookupKey(reportedPath, workingDirectory)
    }

    /** Registers the branch counters of one source file under the same key as its line data.
     *  A header compiled into several translation units contributes its counters more than
     *  once, which the accumulation mirrors. */
    private fun registerBranches(
        key: String,
        total: Int,
        covered: Int,
        branchCoverage: MutableMap<String, BranchCoverage>,
    ) {
        if (total == 0) return
        val existing = branchCoverage[key]
        branchCoverage[key] = BranchCoverage(
            total = (existing?.total ?: 0) + total,
            covered = (existing?.covered ?: 0) + covered,
        )
    }

    /** Registers one source file. Repeated registrations accumulate, which is what a header
     *  compiled into several translation units needs. */
    private fun register(projectData: ProjectData, key: String, hits: Map<Int, Int>) {
        if (hits.isEmpty()) return
        // The platform reads the array by line number and merges repeated setLines calls, so a
        // sparse, line-indexed array is the expected shape.
        val lines = arrayOfNulls<LineData>(hits.keys.max() + 1)
        hits.forEach { (lineNumber, count) -> lines[lineNumber] = lineData(lineNumber, count) }
        @Suppress("UNCHECKED_CAST")
        projectData.getOrCreateClassData(key).setLines(lines as Array<LineData>)
    }

    private fun lineData(lineNumber: Int, hits: Int): LineData = LineData(lineNumber, null).apply {
        // touch() drives the covered status; the loop is capped because gcov counts can reach
        // billions for long-running loops.
        repeat(hits.coerceAtMost(HITS_CAP)) { touch() }
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asInt

    private fun JsonObject.arrayOrNull(name: String): com.google.gson.JsonArray? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonArray

    private val Log = logger<GcovReportParser>()

    private const val TEXT_SUFFIX = ".gcov"
    private const val JSON_SUFFIX = ".gcov.json"
    private const val JSON_GZ_SUFFIX = ".gcov.json.gz"
    private const val HITS_CAP = 10_000
}
