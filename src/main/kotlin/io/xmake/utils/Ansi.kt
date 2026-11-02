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
package io.xmake.utils

/**
 * One ANSI CSI sequence a tool may leave in its piped output, as two alternatives so
 * neither parser family regresses:
 *
 * - with an introducer - ESC, or the 8-bit CSI U+009B - any parameter count reads, zero
 *   included: the plain-text parsers (package queries, xmake --show output) relied on the
 *   introducer to never touch bracketed prose, and `ESC[m` resets strip cleanly;
 * - bare, at least one parameter digit or separator: the stream parsers (sanitizers, tidy,
 *   tests) also strip fragments whose ESC byte a capture path lost, and the one-parameter
 *   minimum is what keeps prose like `[modernize-use-nullptr]` intact there.
 */
private val ANSI_SEQUENCE = Regex("[\\u001B\\u009B]\\[[0-9;]*[A-Za-z]|\\[[0-9;]+[A-Za-z]")

/**
 * [text] without its ANSI control sequences - the one strip every output parser shares.
 * xmake colors its output even when piped, so the parsers of everything xmake prints
 * (sanitizer stderr, clang-tidy findings, test results, package queries) run their lines
 * through this before matching; a single copy replaced five per-parser regexes that had
 * drifted apart, one of which carried the introducers as raw control characters.
 */
fun stripAnsi(text: String): String = ANSI_SEQUENCE.replace(text, "")
