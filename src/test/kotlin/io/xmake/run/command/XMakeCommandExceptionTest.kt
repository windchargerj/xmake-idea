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
import org.junit.Assert.assertNull
import org.junit.Test

/** The failure's own sentence, read from xmake's stable output texts - each fixture
 *  captured live: the invalid-flag and not-found texts from 2.8.7 runs, the version
 *  refusal identical across versions, the password prompt from xmake's own
 *  remote_cache client. */
class XMakeCommandExceptionTest {

    private fun failing(output: String, exitCode: Int = 1) =
        XMakeCommandException(exitCode, "xmake require", output)

    @Test
    fun `an unknown flag reads as the newer-toolkit sentence`() {
        val error = failing("error: Invalid flag: --check\n\$ xmake require ...")
        assertEquals(
            "'--check' is newer than this toolkit - check needs a newer xmake",
            error.summary,
        )
        assertNull(error.versionRefusal)
    }

    @Test
    fun `a not-found ask reads as the no-match sentence`() {
        val error = failing("packages(zstd 1.5.6) not found, maybe they don’t exactly match the configuration")
        assertEquals(
            "no installed package matches 'zstd 1.5.6' on this platform and configuration",
            error.summary,
        )
    }

    @Test
    fun `the version refusal wins the sentence and parses its requirement`() {
        val error = failing(
            "xmake v2.8.7 < v3.0.0, please run xmake f --config before requiring packages\nerror: Invalid flag: --check",
        )
        assertEquals("this project asks for xmake 3.0.0 or newer", error.summary)
        assertEquals("3.0.0", error.versionRefusal)
    }

    @Test
    fun `the password prompt reads as the store-the-token sentence`() {
        val error = failing("Please input user jacky password to connect <127.0.0.1:9692>:", 255)
        assertEquals(
            "xmake is asking for the service password on stdin, which no command channel can answer - store the token first",
            error.summary,
        )
    }

    @Test
    fun `anything else reads its exit code`() {
        val error = failing("note: something unclassifiable", 3)
        assertEquals("xmake exited with code 3", error.summary)
        assertNull(error.versionRefusal)
    }
}
