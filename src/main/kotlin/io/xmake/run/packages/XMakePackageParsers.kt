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
import kotlinx.serialization.json.booleanOrNull

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

    /**
     * Parses one package's `--info --format=json` output: a JSON array whose objects
     * carry the package's data, `references` present exactly when installed. The array
     * is the package's whole dependency closure - the dependencies ride first and the
     * requested package may sit anywhere in it (last in the observed 3.1.2 answers) -
     * so [packageName] picks the element; the query's answer for another package is
     * not this package's info, however well it parses. Null answers "no usable info" -
     * unknown shape, no element carrying the asked name, failed query; a package that
     * exists nowhere is still parsed (version `latest`, no repo).
     */
    /**
     * Parses one package's `--info --format=json` output: a JSON array whose objects
     * carry the package's data, `references` present exactly when installed. The array
     * is the package's whole dependency closure - the dependencies ride first and the
     * requested package may sit anywhere in it (last in the observed 3.1.2 answers) -
     * so [packageName] picks the element; the query's answer for another package is
     * not this package's info, however well it parses. Null answers "no usable info" -
     * unknown shape, no element carrying the asked name, failed query; a package that
     * exists nowhere is still parsed (version `latest`, no repo).
     */
    fun parsePackageInfo(output: String, packageName: String): XMakePackageInfo? {
        val element = jsonPayload(stripAnsi(output)) ?: return null
        val obj = when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
                .firstOrNull { it.requiredName() == askedName(packageName) }
            is JsonObject -> element
            else -> null
        } ?: return null
        return packageInfo(obj)
    }

    /**
     * Parses everything one `--info --format=json` answer carried: every object of the
     * dependency closure, in the order xmake reported it. The asked package's own answer
     * is [parsePackageInfo]; this is the rest of what the query said, for the view to
     * present. A single-object answer is a closure of one.
     */
    fun parsePackageClosure(output: String): List<XMakePackageInfo> {
        val element = jsonPayload(stripAnsi(output)) ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }.map(::packageInfo)
            is JsonObject -> listOf(packageInfo(element))
            else -> emptyList()
        }
    }

    /**
     * Parses the plain-text `--info` answer - the form that predates `--format=json`
     * and the one older toolkits (2.8.7 among them) still answer. The text opens one
     * `require(name):` block per package of the closure, each fact on a `-> key: value`
     * line; the asked name picks its block. The text form carries no `references`
     * section, so the answer reads as not-installed - the honest reading it can give.
     */
    fun parsePackageInfoText(output: String, packageName: String): XMakePackageInfo? {
        val asked = askedName(packageName)
        return textInfoBlocks(stripAnsi(output)).firstOrNull { it.name == asked }?.toInfo()
    }

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
                references = null,
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

    /** The package info one info object carries. */
    private fun packageInfo(obj: JsonObject): XMakePackageInfo = XMakePackageInfo(
        require = obj.string("require").orEmpty(),
        version = obj.string("version"),
        description = obj.string("description"),
        repoName = obj.obj("repo")?.string("name"),
        repoUrl = obj.obj("repo")?.string("url"),
        installdir = obj.string("installdir"),
        cachedir = obj.string("cachedir"),
        platforms = (obj["platforms"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty(),
        references = obj["references"]?.let { refs ->
            (refs as? JsonArray)?.mapNotNull { ref ->
                (ref as? JsonObject)?.let {
                    XMakePackageReference(
                        dir = it.string("dir"),
                        date = it.string("date"),
                        exists = (it["exists"] as? JsonPrimitive)?.booleanOrNull,
                    )
                }
            }
        },
    )

    /**
     * Parses `--search` output into the repository hits plus the `note:`/`error:` lines
     * xmake printed around them; a hit's description may be empty. The diagnostics are the
     * difference between a silent miss and a diagnosis: on xmake 2.8.7 without unzip/7zip
     * the search prints `error: failed to find unzip or 7zip!` and exits 0 with zero hits
     * (docs/roadmap-designs/audit-packages.md, sections 1.6 and 3.1), so the line arrives
     * as stdout and rides out to the view instead of vanishing into "no hits".
     */
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

    /** The bare package name an info object answers: the `require` field may carry a
     *  version ask ("python 3.x") and an external-manager prefix ("vcpkg::zlib"). */
    private fun JsonObject.requiredName(): String? =
        string("require")?.trim()?.substringBefore(" ")?.substringAfterLast("::")?.lowercase()

    /** The bare name one query asks for, normalized the same way [requiredName] reads. */
    private fun askedName(packageName: String): String =
        packageName.trim().substringBefore(" ").substringAfterLast("::").lowercase()
}
