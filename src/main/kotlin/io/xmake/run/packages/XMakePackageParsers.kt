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
package io.xmake.run.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import io.xmake.utils.stripAnsi

/**
 * Parses the output of the `xmake require` queries the packages view drives.
 *
 * Formats per docs/roadmap-designs/xrepo-packages.md, verified on xmake 3.1.2: `--list` and
 * `--search` print plain text (their `--format=json` is silently ignored), `--info` and
 * `--depgraph` emit JSON on stdout. A cold cache prints `checking for platform ...` prologue
 * lines first, and xmake can colorize even piped output, so every parser strips ANSI escapes
 * and drops diagnostic lines before reading - the discipline [io.xmake.utils.info.XMakeInfo]
 * established. Every parser tolerates junk: a shape it does not recognize answers empty or
 * null, never throws, because a misparsed listing must degrade to fewer rows, not a broken view.
 */
internal object XMakePackageParsers {

    private val json = Json { ignoreUnknownKeys = true }

    /** Diagnostic prologue lines start unindented, so they never read as package data. */
    private val DIAGNOSTIC_PREFIX = Regex("""(?i)^\s*(note|warning|error|checking|stack traceback)\s*[: ]""")

    /** `require(<name>): <requirement>` - the package set of a `--list`ing. */
    private val REQUIRE_LINE = Regex("""require\((.+?)\)\s*:\s*(.*)""")

    /** `-> <name>-<version>: <description> (in <repo>)`, description possibly empty. */
    private val SEARCH_LINE = Regex("""->\s*(\S+?)\s*:\s*(.*?)\s*(?:\(in\s+(\S+)\)\s*)?$""")

    /** The `note:` / `error:` lines a search can print instead of hits. Deliberately
     *  narrower than [DIAGNOSTIC_PREFIX]: `checking for platform ...` prologue is noise
     *  no user can act on, while these two name the real blocker. */
    private val SEARCH_DIAGNOSTIC = Regex("""(?i)^\s*(note|error)\s*[: ]""")

    /** Splits `name-version`, where the version starts with a digit or `v` + a digit. The
     *  `v` matters: xrepo keeps the raw `add_versions` strings in the hit tokens, and the
     *  `v` prefix is common there (`zlib-v1.3.2`, `minizip-v1.3.2`) - the digit-only split
     *  left the whole token as the name, and an install built on it asked xmake for a
     *  package that does not exist (docs/roadmap-designs/audit-packages.md, section 2,
     *  verified on 3.1.2). Only `v` is accepted as a letter prefix - the audit saw no other
     *  one, and accepting bare letters would mis-split names that end in a word. The greedy
     *  name part still backtracks to the dash that opens the version, so dashed names
     *  (`minizip-ng-4.2.2`) and dashed versions (`1.2.3-beta1`) split as before. */
    private val NAME_VERSION = Regex("""(.*)-(v?\d.*)""")

    /** How deep the dependency walk follows `deps`; a real graph is shallow, a corrupt one
     *  might not be, and the cap keeps a corrupt cycle from hanging a refresh. */
    private const val MAX_GRAPH_DEPTH = 8

    /**
     * Parses a `require --list` output into the declared packages. JSON is attempted first
     * (a future xmake honoring the listing flag lights up without a plugin change), then
     * the verified text form; `-> ` detail lines are skipped entirely because they reflect
     * cached fetch info, not installed state.
     */
    fun parseRequireList(output: String): List<XMakeRequireEntry> {
        val text = stripAnsi(output)
        jsonPayload(text)?.let { payload ->
            val entries = entriesFromJson(payload)
            if (entries != null) return entries
        }
        return text.lineSequence()
            .mapNotNull { line -> REQUIRE_LINE.find(line.trim())?.destructured }
            .map { (name, requirement) -> XMakeRequireEntry(name.trim(), requirement.trim()) }
            .filter { it.name.isNotEmpty() }
            .toList()
    }

    /** Accepts `[{"name"/"require": ..., "version": ...}, ...]` or a plain string array;
     *  any other shape means "not JSON" and the text parser takes over. */
    private fun entriesFromJson(payload: JsonElement): List<XMakeRequireEntry>? {
        val array = payload as? JsonArray ?: return null
        return array.map { item ->
            when (item) {
                is JsonObject -> XMakeRequireEntry(
                    name = item.string("name") ?: item.string("require") ?: return null,
                    requirement = item.string("version").orEmpty(),
                )
                is JsonPrimitive -> XMakeRequireEntry(item.content, "")
                else -> return null
            }
        }
    }

    fun parsePackageInfoText(output: String, packageName: String): XMakePackageInfo? {
        val asked = askedName(packageName)
        return textInfoBlocks(stripAnsi(output)).firstOrNull { askedName(it.name) == asked }?.toInfo()
    }

    /**
     * Parses every package the plain-text `--info` answer carried - the text form of
     * [parsePackageClosure], so a toolkit that cannot answer the JSON flag still hands
     * over the whole closure, one `require(name):` block per package.
     */
    fun parsePackageClosureText(output: String): List<XMakePackageInfo> =
        textInfoBlocks(stripAnsi(output)).map { it.toInfo() }

    /** One parsed `require(name):` block: the block's name and its `-> key: value` facts. */
    private class TextInfoBlock(val name: String, val facts: Map<String, String>) {

        fun toInfo(): XMakePackageInfo {
            val repo = facts["repo"]?.split(Regex("\\s+")).orEmpty()
            return XMakePackageInfo(
                require = name,
                version = facts["version"],
                description = facts["description"],
                repoName = repo.firstOrNull(),
                repoUrl = repo.getOrNull(1),
                installdir = facts["installdir"],
                cachedir = facts["cachedir"],
                platforms = facts["platforms"]
                    ?.split(",")
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty(),
            )
        }
    }

    /** Splits the text answer into its `require(name):` blocks. */
    private fun textInfoBlocks(text: String): List<TextInfoBlock> {
        val blockName = Regex("""^\s*require\((.+?)\):""")
        val factLine = Regex("""^\s*->\s*([A-Za-z ]+):\s?(.*)$""")
        val blocks = mutableListOf<TextInfoBlock>()
        var name: String? = null
        val facts = mutableMapOf<String, String>()
        fun close() {
            name?.let { blocks.add(TextInfoBlock(it, facts.toMap())) }
            facts.clear()
        }
        text.lineSequence().forEach { line ->
            val block = blockName.find(line)
            if (block != null) {
                close()
                name = block.groupValues[1].trim().substringBefore(" ").substringAfterLast("::").lowercase()
                return@forEach
            }
            if (name == null) return@forEach
            val fact = factLine.find(line) ?: return@forEach
            facts.putIfAbsent(fact.groupValues[1].trim().lowercase(), fact.groupValues[2].trim())
        }
        close()
        return blocks
    }

    /**
     * Parses `require --scan` output - the inventory of what is actually installed. The
     * listing prints one `name-version:` heading per version directory on disk and a
     * `-> <hash>: <plat>, <arch>` child per hash directory under it, tagging a drained
     * directory `empty` and a manifest-less one `invalid` - and a version's heading
     * survives on disk after an uninstall, so a heading alone is not a proof of an
     * install: the version answers only when a child without `empty`/`invalid` rode
     * under it (`unused` - no living project references it any more - still sits on
     * disk, so it stays). Every version answers: two installed versions of one package
     * are two entries, not a last-one-wins collapse.
     *
     * The heading is `name` and version joined with one dash, both of which may contain
     * more dashes (`cpp-httplib-0.11.3:`, `openssl-1.1.1-w:`), and versions may carry a
     * leading `v` (`zstd-v1.5.7:`) - so the split takes the LAST dash that a version
     * starts after: a digit, or a `v` followed by one.
     */
    fun parseInstalledScan(output: String): List<XMakePackageInfo> {
        class Entry(val name: String, val version: String) {
            var installed = false
        }
        val entries = mutableListOf<Entry>()
        var current: Entry? = null
        stripAnsi(output).lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.endsWith(":")) {
                val text = line.removeSuffix(":")
                val cut = text.scanVersionCut()
                current = cut?.let {
                    Entry(text.substring(0, it).lowercase(), text.substring(it + 1)).also { entries.add(it) }
                }
                return@forEach
            }
            if (line.endsWith(", empty") || line.endsWith(", invalid")) return@forEach
            if (line.startsWith("-> ")) current?.installed = true
        }
        return entries.filter { it.installed }.map { entry ->
            XMakePackageInfo(require = entry.name, version = entry.version, description = null, repoName = null,
                repoUrl = null, installdir = null, cachedir = null, platforms = emptyList(), installed = true)
        }
    }

    fun parseSearchResults(output: String): XMakeSearchResults {
        val text = stripAnsi(output)
        val hits = text.lineSequence()
            .mapNotNull { line ->
                val match = SEARCH_LINE.find(line.trim()) ?: return@mapNotNull null
                val (nameVersion, description, repo) = match.destructured
                val (name, version) = splitNameVersion(nameVersion)
                XMakePackageSearchEntry(
                    name = name,
                    version = version,
                    description = description.takeIf(String::isNotEmpty),
                    repo = repo.takeIf(String::isNotEmpty),
                )
            }
            .filter { it.name.isNotEmpty() }
            .toList()
        val diagnostics = text.lineSequence()
            .filter { line -> SEARCH_DIAGNOSTIC.containsMatchIn(line.trim()) }
            .map(String::trim)
            .toList()
        return XMakeSearchResults(hits, diagnostics)
    }

    /** Parses the `--depgraph --format=json` output; null on any shape mismatch, which the
     *  view degrades to "no dependency children". */
    fun parseDepgraph(output: String): XMakePackageDepgraph? {
        val obj = jsonPayload(stripAnsi(output)) as? JsonObject ?: return null
        val packages = (obj["packages"] as? JsonArray)?.mapNotNull { item ->
            (item as? JsonObject)?.let {
                XMakeDepNode(
                    name = it.string("name") ?: return@mapNotNull null,
                    version = it.string("version"),
                    deps = (it["deps"] as? JsonArray)
                        ?.mapNotNull { dep -> (dep as? JsonPrimitive)?.content }
                        .orEmpty(),
                )
            }
        } ?: return null
        val roots = (obj["root_packages"] as? JsonArray)
            ?.mapNotNull { root -> (root as? JsonPrimitive)?.content }
            ?: return null
        return XMakePackageDepgraph(packages, roots)
    }

    /** The dependency children of [name] within [depgraph], depth-limited and cycle-safe:
     *  one node per name on a branch, diamonds allowed, cycles cut. Null when there is no
     *  graph to walk - the row simply carries no children. */
    fun dependencyChildren(depgraph: XMakePackageDepgraph?, name: String): List<XMakeDepNode> {
        if (depgraph == null) return emptyList()
        val byName = depgraph.packages.associateBy(XMakeDepNode::name)
        val children = mutableListOf<XMakeDepNode>()
        collectChildren(byName, name, children, mutableSetOf(name), depth = 0)
        return children
    }

    private fun collectChildren(
        byName: Map<String, XMakeDepNode>,
        name: String,
        children: MutableList<XMakeDepNode>,
        visited: MutableSet<String>,
        depth: Int,
    ) {
        if (depth >= MAX_GRAPH_DEPTH) return
        for (dep in byName[name]?.deps.orEmpty()) {
            if (!visited.add(dep)) continue
            byName[dep]?.let { node ->
                children.add(node)
                collectChildren(byName, dep, children, visited, depth + 1)
            }
            // Removed again so sibling branches may share a dependency; a cycle on one
            // branch is still cut, because the recursion holds its own visit.
            visited.remove(dep)
        }
    }

    private fun splitNameVersion(nameVersion: String): Pair<String, String?> {
        val match = NAME_VERSION.matchEntire(nameVersion) ?: return nameVersion to null
        return match.destructured.let { (name, version) -> name to version }
    }

    /** The JSON document inside [text], if any: the diagnostic lines dropped, the outermost
     *  braces kept. xmake prints prologue and note lines around the payload it emits. */
    private fun jsonPayload(text: String): JsonElement? {
        val cleaned = text.lineSequence()
            .filter(String::isNotBlank)
            .filterNot { DIAGNOSTIC_PREFIX.containsMatchIn(it.trim()) }
            .joinToString("\n")
        val start = cleaned.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return null
        val end = maxOf(cleaned.lastIndexOf(']'), cleaned.lastIndexOf('}'))
        if (end <= start) return null
        return try {
            json.parseToJsonElement(cleaned.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** The bare name one query asks for: the ask may carry a version ("python 3.x")
     *  and an external-manager prefix ("vcpkg::zlib"). */
    private fun askedName(packageName: String): String =
        packageName.trim().substringBefore(" ").substringAfterLast("::").lowercase()

    /** The index of the dash a `name-version` heading splits at: the last one the rest
     *  of the text starts a version after - a digit, or a `v` followed by one - or null
     *  when nothing after any dash reads as a version. */
    private fun String.scanVersionCut(): Int? {
        for (cut in length - 2 downTo 1) {
            if (this[cut] != '-') continue
            val version = substring(cut + 1)
            val startsVersion = version[0].isDigit() ||
                    (version.length > 1 && version[0] == 'v' && version[1].isDigit())
            if (startsVersion) return cut
        }
        return null
    }

    /**
     * Parses `xmake repo --list`'s answer: `local repositories:` / `global repositories:`
     * headings, then one indented line per repository - name, url (or on-disk path for
     * the built-in), optional branch. The scope rides the heading the line follows.
     * Anything outside the headings and the indented rows (a "N repositories were found!"
     * tail, log noise) is ignored.
     */
    fun parseRepositories(output: String): List<XMakeRepoState> {
        val repositories = mutableListOf<XMakeRepoState>()
        var scope = "local"
        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("local repositories", ignoreCase = true) -> scope = "local"
                trimmed.startsWith("global repositories", ignoreCase = true) -> scope = "global"
                line.startsWith(" ") && trimmed.isNotEmpty() -> {
                    val tokens = trimmed.split(Regex("\\s+"))
                    if (tokens.size >= 2) {
                        repositories += XMakeRepoState(
                            name = tokens[0],
                            url = tokens[1],
                            // A parenthesized token opens the description xmake prints
                            // in the same line; a branch never starts with a paren.
                            branch = tokens.getOrNull(2)?.takeUnless { it.startsWith("(") },
                            scope = scope,
                        )
                    }
                }
            }
        }
        return repositories
    }
}
