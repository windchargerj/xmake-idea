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
package io.xmake.run.sanitizer

/**
 * The sanitizers xmake can build with. The instrumentation comes from the
 * `build.sanitizer.*` policy (xmake >= 2.8), not from the `mode.asan`-style rules - those
 * are deprecated wrappers that only fire when the project attached them, so a sanitized
 * run configures with `-m debug` plus the policy instead and works on every project.
 */
enum class SanitizerKind(
    val displayName: String,
    /** The `build.sanitizer.*` policy that instruments the build for this sanitizer. */
    val policy: String,
    /** The runtime's option variable: ASAN_OPTIONS and friends. */
    val optionVariable: String,
) {
    ADDRESS("AddressSanitizer", "build.sanitizer.address", "ASAN_OPTIONS"),
    LEAK("LeakSanitizer", "build.sanitizer.leak", "LSAN_OPTIONS"),
    MEMORY("MemorySanitizer", "build.sanitizer.memory", "MSAN_OPTIONS"),
    THREAD("ThreadSanitizer", "build.sanitizer.thread", "TSAN_OPTIONS"),
    UNDEFINED("UndefinedBehaviorSanitizer", "build.sanitizer.undefined", "UBSAN_OPTIONS"),
    ;

    /**
     * The runtime options a sanitized run sets when the user set none of their own:
     * reports stay symbolized, UBSan prints its stacks and error types, and every runtime
     * emits a dedup token the parser could group by.
     */
    val defaultOptions: String
        get() = when (this) {
            ADDRESS, LEAK -> "symbolize=1:dedup_token_length=3"
            MEMORY -> "symbolize=1:dedup_token_length=3"
            THREAD -> "symbolize=1:dedup_token_length=3"
            UNDEFINED -> "symbolize=1:print_stacktrace=1:report_error_type=1:dedup_token_length=3"
        }

    companion object {
        /** The option variables every runtime reads, in declaration order. */
        val OPTION_VARIABLES: Set<String> = entries.mapTo(mutableSetOf()) { it.optionVariable }
    }
}
