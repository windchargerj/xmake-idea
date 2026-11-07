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
package io.xmake.utils.cli

/**
 * One package ask in xmake's grammar, as a value: a [name] that may carry an
 * external-manager prefix (`vcpkg::zlib`) or a repository qualifier (`xmake-repo@tbox`),
 * and the [version] ask - an exact version ("1.5.6"), a range (">=1.2.11"), a branch
 * ("master"), or null for whatever the project resolves.
 *
 * The grammar lives here and nowhere else. xmake's require parser
 * (`package.lua:_parse_require`) splits a spec on whitespace: the first token is the
 * package, the rest is the version - and reads `@` as a *repository* separator, so
 * "zstd@1.5.6" would install the package "1.5.6" from a repository "zstd". [render]
 * is therefore the only place the space form is produced, and [parse] only ever splits
 * on whitespace; an `@` travels inside the name untouched, because a caller who means
 * a version never writes one there.
 *
 * The grammar is xrepo's too: `xrepo install` takes the same ask, and xmake's own
 * modules branch on XREPO_WORKING to serve both CLIs with one parser. The spec names
 * no CLI for exactly that reason - `createRequire*` methods say which surface a
 * command goes to today; a global xrepo mode would reuse this type unchanged.
 */
data class XMakePackageSpec(val name: String, val version: String? = null) {

    init {
        require(name.isNotBlank()) { "Package name must not be blank" }
        require(name.none(Char::isWhitespace)) { "Package name must not carry whitespace: $name" }
    }

    /** The exact-version form of this spec, or itself when [version] is blank. */
    fun pinnedTo(version: String?): XMakePackageSpec =
        if (version.isNullOrBlank()) this else copy(version = version)

    /** The command-line form: the one string xmake's require parser accepts. */
    fun render(): String = version?.let { "$name $it" } ?: name

    override fun toString(): String = render()

    companion object {

        /** Parses a raw ask - what a dialog, a details link, or a persisted string
         *  carries - into a spec. The first whitespace splits the ask; the rest rides
         *  along as the version verbatim, ranges included. A blank ask resolves like
         *  a bare name. */
        fun parse(raw: String): XMakePackageSpec {
            val parts = raw.trim().split(WHITESPACE, limit = 2)
            return XMakePackageSpec(parts[0], parts.getOrNull(1))
        }

        private val WHITESPACE = Regex("""\s+""")
    }
}
