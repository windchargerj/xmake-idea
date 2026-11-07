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
 * One package ask in xmake's own grammar, as a value: a [name] that may carry an
 * external-manager prefix (`vcpkg::zlib`) or a repository qualifier (`xmake-repo@tbox`),
 * and an optional version [ask] - an exact version ("1.5.6"), a range (">=1.2.11"),
 * a branch ("master"), or the default resolution when null.
 *
 * The grammar lives here and nowhere else. xmake's require parser
 * (`package.lua:_parse_require`) splits a spec on whitespace: the first token is the
 * package, the rest is the version ask - and reads `@` as a *repository* separator, so
 * "zstd@1.5.6" would install the package "1.5.6" from a repository "zstd". [render] is
 * therefore the only place the space form is produced, and [parse] only ever splits on
 * whitespace; an `@` travels inside the name untouched, because a caller who means a
 * version never writes one there.
 */
data class XPackageSpec(val name: String, val ask: String? = null) {

    init {
        require(name.isNotBlank()) { "Package name must not be blank" }
        require(name.none(Char::isWhitespace)) { "Package name must not carry whitespace: $name" }
    }

    /** The exact-version form of this spec, or itself when [version] is blank. */
    fun pinnedTo(version: String?): XPackageSpec =
        if (version.isNullOrBlank()) this else copy(ask = version)

    /** The command-line form: the one string xmake's require parser accepts. */
    fun render(): String = ask?.let { "$name $it" } ?: name

    override fun toString(): String = render()

    companion object {

        /** Parses a raw ask - what a dialog, a details link, or a persisted string
         *  carries - into a spec. The first whitespace splits the ask; the rest rides
         *  along as the version ask verbatim, ranges included. A blank ask resolves
         *  like a bare name. */
        fun parse(raw: String): XPackageSpec {
            val text = raw.trim()
            val split = text.indexOfFirst(Char::isWhitespace)
            return if (split < 0) {
                XPackageSpec(text)
            } else {
                XPackageSpec(
                    text.substring(0, split),
                    text.substringFromNextNonWhitespace(split),
                )
            }
        }

        private fun String.substringFromNextNonWhitespace(from: Int): String =
            substring(from).trim()
    }
}
