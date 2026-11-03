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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.utils.cli

/**
 * One marked payload a bundled script prints: the machine-readable records between the
 * script's `__begin__`/`__end__` markers (see XMakeCommandFactory's lua queries and the
 * script headers for the per-script grammars). Everything xmake logs ahead of the markers
 * is human prose the parsers must skip.
 */
private val MARKED_PAYLOAD = Regex("__begin__([\\s\\S]*?)__end__")

/**
 * The records [output] carries between its `__begin__`/`__end__` markers, or null when it
 * carries none - the script never ran to its first marker. One extraction serves every
 * bundled-script parser (pack enumeration, services probe, debug target resolution), so a
 * protocol change is one edit; each parser keeps its own line grammar and its own
 * empty-payload semantics on top of the returned text.
 */
fun markerPayload(output: String): String? =
    MARKED_PAYLOAD.find(output.trim())?.groupValues?.get(1)
