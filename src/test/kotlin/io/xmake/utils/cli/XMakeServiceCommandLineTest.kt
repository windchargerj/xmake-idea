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

import io.xmake.project.toolkit.ToolkitHost
import org.junit.Assert.assertEquals
import org.junit.Test

class XMakeServiceCommandLineTest {

    @Test
    fun `builds the service verb and kind flags`() {
        val line = xmake(CliInstallation("xmake", ToolkitHost())).apply {
            service {
                verb = XMakeServiceVerb.CONNECT
                kind = XMakeServiceKind.REMOTE_BUILD
            }
        }
        assertEquals(listOf("service", "--connect", "--remote"), line.arguments)
    }

    @Test
    fun `keeps the remote cache kind on xmake's --ccache flag`() {
        val line = xmake(CliInstallation("xmake", ToolkitHost())).apply {
            service {
                verb = XMakeServiceVerb.DISCONNECT
                kind = XMakeServiceKind.REMOTE_CACHE
            }
        }
        assertEquals(listOf("service", "--disconnect", "--ccache"), line.arguments)
    }

    @Test
    fun `adds no kind flag when only the verb is given`() {
        val line = xmake(CliInstallation("xmake", ToolkitHost())).apply {
            service { verb = XMakeServiceVerb.RECONNECT }
        }
        assertEquals(listOf("service", "--reconnect"), line.arguments)
    }
}
