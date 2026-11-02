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
package io.xmake.run.pack

/**
 * Static per-format pack metadata. `KNOWN` is the validation superset the configuration
 * check measures user-entered formats against - it deliberately covers more than any one
 * xmake install ships, because generators are added (and platform-gated) across versions
 * (docs/roadmap-designs/packaging.md, F4: this machine's 3.1.2 has no `ifw` generator even
 * though newer installs do). The pickers never read it: the editor offers only the formats
 * the selected xpack declares, because `-f` filters, it cannot add (F7) - an undeclared
 * format is silently skipped by xmake, and a typo here would pack nothing.
 */
object XMakePackFormats {

    /** Every format id the pack generators across xmake versions answer to. */
    val KNOWN: Set<String> = linkedSetOf(
        "nsis", "wix", "inno", "deb", "srpm", "rpm",
        "runself", "targz", "tarxz", "zip", "srctargz", "srctarxz", "srczip",
        "dmg", "appimage", "ifw",
    )

    /**
     * The artifact file extension one packed format produces, from xpack's `extension()`
     * table (docs/roadmap-designs/packaging.md, F5); null when the generator is version- or
     * platform-gated and this plugin carries no verified extension (`ifw`).
     */
    fun extensionOf(format: String): String? = EXTENSIONS[format]

    private val EXTENSIONS: Map<String, String> = mapOf(
        "wix" to ".msi",
        "nsis" to ".exe",
        "inno" to ".exe",
        "zip" to ".zip",
        "srczip" to ".zip",
        "targz" to ".tar.gz",
        "srctargz" to ".tar.gz",
        "tarxz" to ".tar.xz",
        "srctarxz" to ".tar.xz",
        "dmg" to ".dmg",
        "appimage" to ".AppImage",
        "runself" to ".gz.run",
        "deb" to ".deb",
        "srpm" to ".src.rpm",
        "rpm" to ".rpm",
    )
}
