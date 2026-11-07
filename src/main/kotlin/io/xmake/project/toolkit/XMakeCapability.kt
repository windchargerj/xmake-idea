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
package io.xmake.project.toolkit

/**
 * One CLI surface fact, with the xmake version that introduced it. The matrix is the
 * single source both the command factory and the panels consult: the factory refuses to
 * emit a flag the toolkit cannot answer, the panels disable the button and word the
 * tooltip from the same answer - so a capability gap degrades in the UI instead of
 * dying in a console as `error: Invalid flag`.
 *
 * The since-versions are verified at the boundaries this machine holds - 2.8.7 (WSL)
 * and 3.1.2 (Windows): a capability's flag was run or read from xmake's own option
 * table on both sides. Intermediate versions inherit the nearest verified answer and
 * the matrix marks them unverified; unknown versions answer [XMakeCapability.onUnknown].
 */
enum class XMakeCapability(
    val since: XMakeVersion,
    /** What the capability names on the command line - the tooltip's fact. */
    val label: String,
    /** How an xmake whose version is unknown answers: false would refuse everything,
     *  true lets it through and lets the (gracefully degrading) parse decide. */
    val onUnknown: Boolean = false,
) {
    /** `require --info --format=json`; older toolkits answer their usage text. The
     *  plain-text info form predates it and every toolkit reads it. */
    REQUIRE_FORMAT_JSON(XMakeVersion.of(3, 1, 2), "require --info --format=json"),

    /** `require --download`: source archives without installing. */
    REQUIRE_DOWNLOAD(XMakeVersion.of(3, 1, 2), "require --download"),

    /** `require --check`: platform and toolchain support of a package. */
    REQUIRE_CHECK(XMakeVersion.of(3, 1, 2), "require --check"),

    /** `require --scan`: the installed-package inventory. Verified on 2.8.7 and 3.1.2
     *  directly; older versions are unverified, so an unknown version lets it through. */
    REQUIRE_SCAN(XMakeVersion.of(2, 8, 7), "require --scan", onUnknown = true),

    /** `service --status`: the daemon and connection report. Absent from 2.8.7's help. */
    SERVICE_STATUS(XMakeVersion.of(3, 1, 2), "service --status"),

    /** `service --logs`: the daemon's log output. Absent from 2.8.7's help. */
    SERVICE_LOGS(XMakeVersion.of(3, 1, 2), "service --logs"),
    ;

    /** The requirement as a reader meets it in a tooltip. */
    val requirement: String
        get() = "xmake ${since.display}"
}

/**
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
         *  [UNKNOWN] - the caller's `onUnknown` decides what that means. */
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

/** Whether this toolkit's probed version answers [capability]'s requirement. */
fun Toolkit.supports(capability: XMakeCapability): Boolean {
    val version = XMakeVersion.parse(version)
    return if (version == XMakeVersion.UNKNOWN) {
        capability.onUnknown
    } else {
        version >= capability.since
    }
}
