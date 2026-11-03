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
package io.xmake.project.console

import io.xmake.utils.cli.XMakeServiceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XMakeServicesProbeParserTest {

    @Test
    fun `parses a full payload with warnings logged ahead of the markers`() {
        val output = """
            checking for platform ... windows (x64)
            warning: something xmake logs first
            __begin__
            ccache-enabled: true
            cache-program: C:\tools\ccache\ccache.exe
            service: kind=remote_build endpoint=192.168.0.192:9691 connected=true reachable=no token=yes
            service: kind=distcc_build endpoint=127.0.0.1:9693 connected=false reachable=yes token=no
            service: kind=remote_cache endpoint=none connected=false reachable=untested token=no
            __end__
        """.trimIndent() + "\n"

        val snapshot = XMakeServicesProbeParser.parse(output)!!

        assertTrue(snapshot.cache.enabled)
        assertEquals("C:\\tools\\ccache\\ccache.exe", snapshot.cache.program)
        val remote = snapshot.services.getValue(XMakeServiceKind.REMOTE_BUILD)
        assertEquals("192.168.0.192:9691", remote.endpoint)
        assertTrue(remote.connected)
        assertEquals(XMakeReachability.NO, remote.reachable)
        assertTrue(remote.tokenKnown)
        val distcc = snapshot.services.getValue(XMakeServiceKind.DISTCC_BUILD)
        assertFalse(distcc.connected)
        assertEquals(XMakeReachability.YES, distcc.reachable)
        assertFalse(distcc.tokenKnown)
        assertEquals(XMakeReachability.UNTESTED, snapshot.services.getValue(XMakeServiceKind.REMOTE_CACHE).reachable)
    }

    @Test
    fun `reads the no cache tool and unconfigured services state`() {
        val output = """
            __begin__
            ccache-enabled: true
            cache-program: none
            service: kind=remote_build endpoint=none connected=false reachable=untested token=no
            service: kind=distcc_build endpoint=none connected=false reachable=untested token=no
            service: kind=remote_cache endpoint=none connected=false reachable=untested token=no
            __end__
        """.trimIndent() + "\n"

        val snapshot = XMakeServicesProbeParser.parse(output)!!

        assertTrue(snapshot.cache.enabled)
        assertNull(snapshot.cache.program)
        assertTrue(snapshot.services.values.all { it.endpoint == null && !it.connected && !it.tokenKnown })
    }

    @Test
    fun `skips unknown service kinds for forward compatibility`() {
        val output = """
            __begin__
            ccache-enabled: false
            cache-program: /usr/bin/sccache
            service: kind=remote_build endpoint=127.0.0.1:9691 connected=false reachable=yes token=yes
            service: kind=future_service endpoint=127.0.0.1:1 connected=true reachable=yes token=yes
            __end__
        """.trimIndent() + "\n"

        val snapshot = XMakeServicesProbeParser.parse(output)!!

        assertFalse(snapshot.cache.enabled)
        assertEquals("/usr/bin/sccache", snapshot.cache.program)
        assertEquals(setOf(XMakeServiceKind.REMOTE_BUILD), snapshot.services.keys)
    }

    @Test
    fun `answers null when the probe produced no payload`() {
        assertNull(XMakeServicesProbeParser.parse("checking for platform ... windows\n"))
        assertNull(XMakeServicesProbeParser.parse(""))
    }
}
