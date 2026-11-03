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
package io.xmake.run.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The static pack metadata: the validation superset and the per-format artifact extensions
 *  verified against the xmake 3.1.2 generators (docs/roadmap-designs/packaging.md, F5). */
class XMakePackFormatsTest {

    @Test
    fun `the known set carries every format the task verified`() {
        assertEquals(
            setOf(
                "nsis", "wix", "inno", "deb", "srpm", "rpm",
                "runself", "targz", "tarxz", "zip", "srctargz", "srctarxz", "srczip",
                "dmg", "appimage", "ifw",
            ),
            XMakePackFormats.KNOWN,
        )
    }

    @Test
    fun `extensions match the verified generator table`() {
        assertEquals(".msi", XMakePackFormats.extensionOf("wix"))
        assertEquals(".exe", XMakePackFormats.extensionOf("nsis"))
        assertEquals(".exe", XMakePackFormats.extensionOf("inno"))
        assertEquals(".zip", XMakePackFormats.extensionOf("zip"))
        assertEquals(".zip", XMakePackFormats.extensionOf("srczip"))
        assertEquals(".tar.gz", XMakePackFormats.extensionOf("targz"))
        assertEquals(".tar.gz", XMakePackFormats.extensionOf("srctargz"))
        assertEquals(".tar.xz", XMakePackFormats.extensionOf("tarxz"))
        assertEquals(".tar.xz", XMakePackFormats.extensionOf("srctarxz"))
        assertEquals(".dmg", XMakePackFormats.extensionOf("dmg"))
        assertEquals(".AppImage", XMakePackFormats.extensionOf("appimage"))
        assertEquals(".gz.run", XMakePackFormats.extensionOf("runself"))
        assertEquals(".deb", XMakePackFormats.extensionOf("deb"))
        assertEquals(".src.rpm", XMakePackFormats.extensionOf("srpm"))
        assertEquals(".rpm", XMakePackFormats.extensionOf("rpm"))
    }

    @Test
    fun `the ifw generator has no verified extension on this install`() {
        assertNull(XMakePackFormats.extensionOf("ifw"))
        assertTrue("ifw" in XMakePackFormats.KNOWN)
    }

    @Test
    fun `unknown formats have no extension`() {
        assertNull(XMakePackFormats.extensionOf("zips"))
    }
}
