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

/**
 * One package the project declares, as `xmake require --list` printed it.
 *
 * [requirement] is the version (or version range) the `xmake.lua` asked for - it says what
 * the project wants, not what is installed; the installed truth is [XMakePackageInfo].
 */
data class XMakeRequireEntry(
    val name: String,
    val requirement: String,
)

/**
 * What one package's info ask reported, and whether the store backs it.
 *
 * [version] is the resolution this project gets (a package that exists nowhere reports
 * `latest` with no repo, so a missing [repoName] means "not found in the configured
 * repositories", not a failed query). [installed] is the store's answer - the asked
 * version sits in the installed inventory the query scan section read - and it is
 * the one installed truth the view reads; the listing's `-> ` detail lines survive an
 * uninstall (docs/roadmap-designs/xrepo-packages.md, verified on 3.1.2), so nothing
 * else may vouch.
 */
data class XMakePackageInfo(
    val require: String,
    val version: String?,
    val description: String?,
    val repoName: String?,
    val repoUrl: String?,
    val installdir: String?,
    val cachedir: String?,
    val platforms: List<String>,
    val installed: Boolean = false,
)

/** One repository hit of `xmake require --search`. */
data class XMakePackageSearchEntry(
    val name: String,
    val version: String?,
    val description: String?,
    val repo: String?,
)

/**
 * One `--search` answer: the repository hits and the `note:`/`error:` lines xmake printed
 * around them. The diagnostics matter exactly when [hits] is empty - a search that answers
 * exit 0 with no hits and an `error:` line is a broken toolchain (xmake 2.8.7 without
 * unzip/7zip), not a misspelled term (docs/roadmap-designs/audit-packages.md, section 1.6).
 */
data class XMakeSearchResults(
    val hits: List<XMakePackageSearchEntry>,
    val diagnostics: List<String> = emptyList(),
)

/**
 * The `xmake require --depgraph --format=json` output: every package node and which other
 * packages it needs. [rootPackages] are the project's direct requirements; the view nests
 * the rest under their dependents.
 */
data class XMakePackageDepgraph(
    val packages: List<XMakeDepNode>,
    val rootPackages: List<String>,
)

/** One dependency-graph node: a package and the names of the packages it needs. */
data class XMakeDepNode(
    val name: String,
    val version: String?,
    val deps: List<String>,
)

/**
 * The packages view's state at one moment. [infos] maps a queried package name to its info,
 * or to null when the query ran but produced nothing usable - the null stops the view from
 * re-querying on every selection. [closures] maps the same name to everything the query
 * answered: the package's whole dependency closure, the asked package included, each
 * element carrying its resolved version and description. [searchResults] is non-null while
 * the tree shows the hits as its filtered view; the view survives refreshes (an install
 * from a hit lands with the hits still shown) and is left only by the explicit exits -
 * Esc, or an emptied field.
 */
data class XMakePackagesSnapshot(
    val packages: List<XMakeRequireEntry>,
    val depgraph: XMakePackageDepgraph?,
    val infos: Map<String, XMakePackageInfo?> = emptyMap(),
    val closures: Map<String, List<XMakePackageInfo>> = emptyMap(),
    val searchResults: XMakeSearchResults? = null,
    /** The `--scan` inventory of what is actually installed, null until scanned. */
    val installed: List<XMakePackageInfo>? = null,
    /** The versions of each package the store holds on disk, one targeted `--scan <name>`
     *  per selection - multi-version truth the details page's VERSIONS section and the
     *  uninstall flow read. The full inventory lands in [installed] only when the
     *  installed view is toggled; this map is what a plain selection collects. */
    val installedVersions: Map<String, List<String>> = emptyMap(),
    /** Why an info query answered nothing usable, per asked name: the parse's own
     *  complaint. The no-info state in the details pane reads it instead of a blank
     *  "answered nothing" - "xmake answered garbage" and "never asked" stay apart. */
    val infoFailures: Map<String, String> = emptyMap(),
    /** The package repositories `xmake repo --list` reports, project-local first. */
    val repositories: List<XMakeRepoState> = emptyList(),
)

/**
 * One package repository `xmake repo --list` reported: its name (the key the `repo`
 * fields and the add/remove verbs speak), its url or on-disk path, the branch a remote
 * git repository pins, and the scope the listing found it under.
 */
data class XMakeRepoState(
    val name: String,
    val url: String,
    val branch: String?,
    /** "local" or "global"; the built-in repository reports under "global". */
    val scope: String,
)
