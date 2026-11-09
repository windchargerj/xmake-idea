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
 * One xmake version, ordered. The raw string the detector captures
 * ("v3.1.2+HEAD.5d21e9daf", "2.8.7") is display text; comparisons need numbers.
 *//**
 * One xmake version, ordered. The raw string the detector captures
 * ("v3.1.2+HEAD.5d21e9daf", "2.8.7") is display text; comparisons need numbers.
 */
data class XMakeVersion(val major: Int, val minor: Int, val patch: Int, val raw: String) :
    Comparable<XMakeVersion> {

    override fun compareTo(other: XMakeVersion): Int = compareValuesBy(
        this, other,
        XMakeVersion::major, XMakeVersion::minor, XMakeVersion::patch,
    )

    /** The display form without the suffix the version string carried. */
    val display: String
        get() = "$major.$minor.$patch"

    companion object {

        /** The answer for a toolkit whose version never got probed. */
        val UNKNOWN = XMakeVersion(0, 0, 0, "")

        fun of(major: Int, minor: Int, patch: Int): XMakeVersion =
            XMakeVersion(major, minor, patch, "$major.$minor.$patch")

        /** Parses the detector's capture: a leading `v`, up to three numeric parts, and
         *  anything after a `+`/`-` suffix is display noise. Unparseable text answers
         *  [UNKNOWN] - and refuses every capability. */
        fun parse(raw: String): XMakeVersion {
            val match = VERSION.find(raw.trim()) ?: return UNKNOWN
            val (major, minor, patch) = match.destructured
            return XMakeVersion(
                major.toIntOrNull() ?: 0,
                minor.toIntOrNull() ?: 0,
                patch.toIntOrNull() ?: 0,
                raw,
            )
        }

        private val VERSION = Regex("""v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
    }
}
