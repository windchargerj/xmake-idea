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

import io.xmake.utils.cli.markerPayload

/** One xpack() package the enumeration script reported: its name and the formats it declares. */
data class XMakePackEntry(
    val name: String,
    val formats: List<String>,
)

/**
 * Parses the machine-readable payload the bundled xpacks.lua script prints between its
 * `__begin__`/`__end__` markers: one `name<TAB>formats` line per declared xpack, the
 * formats comma-separated, empty when the xpack declares none. Anything xmake logs ahead
 * of the markers is ignored, and an output without markers answers an empty list - the
 * query produced nothing (e.g. the project carries no `includes("@builtin/xpack")`).
 */
object XMakePackEnumeration {

    fun parse(output: String): List<XMakePackEntry> {
        val payload = markerPayload(output) ?: return emptyList()
        return payload.lineSequence().mapNotNull(::parseEntry).toList()
    }

    private fun parseEntry(line: String): XMakePackEntry? {
        // The script prints the tab on every line, even with an empty formats column, so the
        // tab is the discriminator: nothing else xmake logs into the payload carries one.
        // The line is split before any trimming - a trailing tab is meaningful, not noise.
        val parts = line.split('\t', limit = 2)
        if (parts.size != 2) return null
        val name = parts[0].trim()
        if (name.isEmpty() || name.any { it.isWhitespace() }) return null
        val formats = parts[1].split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        return XMakePackEntry(name, formats)
    }
}
