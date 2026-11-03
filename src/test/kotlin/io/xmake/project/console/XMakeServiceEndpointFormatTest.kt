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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XMakeServiceEndpointFormatTest {

    @Test
    fun `accepts host and IPv4 literals with numeric ports`() {
        assertEquals("192.168.0.192" to 9691, XMakeServiceEndpointFormat.parse("192.168.0.192:9691"))
        assertEquals("build.internal" to 9693, XMakeServiceEndpointFormat.parse("build.internal:9693"))
        assertEquals("localhost" to 1, XMakeServiceEndpointFormat.parse("localhost:1"))
        assertEquals("localhost" to 65535, XMakeServiceEndpointFormat.parse("localhost:65535"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("127.0.0.1" to 9692, XMakeServiceEndpointFormat.parse("  127.0.0.1:9692 "))
    }

    @Test
    fun `rejects anything the xmake client would not connect to`() {
        assertNull(XMakeServiceEndpointFormat.parse(""))
        assertNull(XMakeServiceEndpointFormat.parse(":9691"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1:"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1:abc"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1:0"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1:65536"))
        assertNull(XMakeServiceEndpointFormat.parse("127.0.0.1:9691:extra"))
    }
}
