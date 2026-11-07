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
package io.xmake.run.command

/**
 * What a failed xmake command actually means, classified once at the executor's single
 * failure site from xmake's own output texts. The texts are stable across the versions
 * this plugin serves - each pattern below was captured live on 2.8.7 and 3.1.2 unless a
 * comment says otherwise - and each class carries the data a panel needs to narrate or
 * to recover, instead of an output blob a caller must re-read.
 */
internal sealed class XMakeCommandFailure {

    /** The sentence a panel or a notification reads aloud - the taxonomy owns the
     *  wording, so every consumer narrates one failure the same way. */
    fun describe(): String = when (this) {
        is InvalidFlag -> "'$flag' is newer than this toolkit - ${flag.trimStart('-')} needs a newer xmake"
        is PackageNotFound -> "no installed package matches '$ask' on this platform and configuration"
        AuthRequired -> "xmake is asking for the service password on stdin, which no command channel can answer - store the token first"
        is VersionRefusal -> "this project asks for xmake $required or newer"
        is Unclassified -> "xmake exited with code $exitCode"
    }

    /** `error: Invalid flag: --download` - the flag is newer than the toolkit. The
     *  capability matrix (XMakeCapability) is what keeps this from happening; it lands
     *  here when something called without asking. */
    data class InvalidFlag(val flag: String) : XMakeCommandFailure()

    /** `packages(zstd 1.5.6) not found, maybe they don't exactly match the
     *  configuration` - the ask resolved to nothing installed, or nothing matching the
     *  project's platform/config. [ask] is the text inside xmake's parentheses. */
    data class PackageNotFound(val ask: String) : XMakeCommandFailure()

    /** The connect would prompt for a password on stdin
     *  ("Please input user <user> password to connect <host:port>:") - the command
     *  channel cannot answer, so xmake hangs. Recoverable: store the token first. */
    data object AuthRequired : XMakeCommandFailure()

    /** A version refusal the classifier saw but no caller specialized - the executor
     *  itself turns this into XMakeVersionRefusalException before it reaches callers. */
    data class VersionRefusal(val required: String) : XMakeCommandFailure()

    /** Anything else: the exit code and the merged output travel with it. */
    data class Unclassified(val exitCode: Int, val output: String) : XMakeCommandFailure()

    companion object {

        /** Classifies one failed run's output. The order matters: the version refusal
         *  names flags in its explanation, so it must win over InvalidFlag. */
        fun classify(exitCode: Int, output: String): XMakeCommandFailure {
            VERSION_REFUSAL.find(output)?.let { return XMakeCommandFailure.VersionRefusal(it.groupValues[1]) }
            INVALID_FLAG.find(output)?.let { return XMakeCommandFailure.InvalidFlag(it.groupValues[1]) }
            NOT_FOUND.find(output)?.let { return XMakeCommandFailure.PackageNotFound(it.groupValues[1]) }
            if (AUTH_REQUIRED.containsMatchIn(output)) return XMakeCommandFailure.AuthRequired
            return XMakeCommandFailure.Unclassified(exitCode, output)
        }

        /** xmake 2.8.7 and 3.x, identical text: "xmake v3.1.2 < v3.2.1, please run …". */
        private val VERSION_REFUSAL = Regex("""xmake v\S+ < v(\S+), please run""")

        /** Live on 2.8.7: `error: Invalid flag: --check`. */
        private val INVALID_FLAG = Regex("""error: Invalid flag: (--?\S+)""")

        /** Live on 2.8.7: `packages(zstd 1.5.6) not found, maybe they don't exactly
         *  match the configuration`. The apostrophe xmake prints is a right single
         *  quote; the classifier only needs the prefix. */
        private val NOT_FOUND = Regex("""packages\((.+?)\) not found""")

        /** xmake 2.8.7 `private/service/remote_cache/client.lua`: "Please input user
         *  <user> password to connect <host:port>:". */
        private val AUTH_REQUIRED = Regex("""Please input user \S+ password to connect""")
    }
}
