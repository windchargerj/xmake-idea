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

import org.junit.Assert.assertEquals
import org.junit.Test

/** The classifier's fixtures, each captured live: the invalid-flag and not-found texts
 *  from 2.8.7 runs, the version refusal identical across versions, the password prompt
 *  from xmake's own remote_cache client. */
class XMakeFailureClassifierTest {

    @Test
    fun `an unknown flag classifies with its name`() {
        val failure = XMakeCommandFailure.classify(1, "error: Invalid flag: --check\n\$ xmake require ...")
        assertEquals(XMakeCommandFailure.InvalidFlag("--check"), failure)
        assertEquals(
            "'--check' is newer than this toolkit - check needs a newer xmake",
            failure.describe(),
        )
    }

    @Test
    fun `a not-found ask classifies with its text`() {
        val failure = XMakeCommandFailure.classify(
            1,
            "packages(zstd 1.5.6) not found, maybe they don’t exactly match the configuration",
        )
        assertEquals(XMakeCommandFailure.PackageNotFound("zstd 1.5.6"), failure)
        assertEquals(
            "no installed package matches 'zstd 1.5.6' on this platform and configuration",
            failure.describe(),
        )
    }

    @Test
    fun `the version refusal wins over any flag talk in the same output`() {
        val output = """
            xmake v2.8.7 < v3.0.0, please run xmake f --config before requiring packages
            error: Invalid flag: --check
        """.trimIndent()
        val failure = XMakeCommandFailure.classify(1, output)
        assertEquals(XMakeCommandFailure.VersionRefusal("3.0.0"), failure)
    }

    @Test
    fun `the password prompt classifies as auth`() {
        val failure = XMakeCommandFailure.classify(
            255,
            "Please input user jacky password to connect <127.0.0.1:9692>:",
        )
        assertEquals(XMakeCommandFailure.AuthPrompt, failure)
    }

    @Test
    fun `anything else rides as other with its evidence`() {
        val failure = XMakeCommandFailure.classify(3, "note: something unclassifiable")
        assertEquals(XMakeCommandFailure.Other(3, "note: something unclassifiable"), failure)
    }
}
