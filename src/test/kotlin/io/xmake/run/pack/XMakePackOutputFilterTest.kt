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
import java.nio.file.Path

/** The `packing <path> ..` line grammar and the working-directory anchoring the filter and
 *  the state's artifact collector share. The platform-side mapping (WSL/SSH) runs through
 *  [io.xmake.run.command.RemotePathMapper], which needs a live project and is covered by
 *  the mapper's own flows, not here. */
class XMakePackOutputFilterTest {

    @Test
    fun `parses the packing line xmake prints for an artifact`() {
        assertEquals(
            "build\\xpack\\hello\\hello.zip",
            PackOutputFilter.parsePackingPath("packing build\\xpack\\hello\\hello.zip .. "),
        )
        assertEquals(
            "build/xpack/hello/hello.zip",
            PackOutputFilter.parsePackingPath("packing build/xpack/hello/hello.zip ..\n"),
        )
    }

    @Test
    fun `a path with spaces survives the parse`() {
        assertEquals(
            "my dir/my pack.zip",
            PackOutputFilter.parsePackingPath("packing my dir/my pack.zip .."),
        )
    }

    @Test
    fun `leaves every other line alone`() {
        assertNull(PackOutputFilter.parsePackingPath("pack ok"))
        assertNull(PackOutputFilter.parsePackingPath("packing without the trailing dots"))
        assertNull(PackOutputFilter.parsePackingPath("[100%] compiling.release main.cpp"))
        assertNull(PackOutputFilter.parsePackingPath(""))
    }

    @Test
    fun `absolute paths of either host form are kept as printed`() {
        assertEqualsUnix(
            "C:/dist/demo-1.0.zip",
            PackOutputFilter.anchorHostPath("C:\\dist\\demo-1.0.zip", "/some/project"),
        )
        assertEqualsUnix(
            "/home/user/proj/build/xpack/hello.zip",
            PackOutputFilter.anchorHostPath("/home/user/proj/build/xpack/hello.zip", "whatever"),
        )
    }

    @Test
    fun `relative paths anchor at the pack command's working directory`() {
        assertEqualsUnix(
            "/home/user/proj/build/xpack/hello/hello.zip",
            PackOutputFilter.anchorHostPath("build/xpack/hello/hello.zip", "/home/user/proj"),
        )
    }

    @Test
    fun `a relative path without a working directory has no anchor`() {
        assertNull(PackOutputFilter.anchorHostPath("build/x.zip", ""))
    }

    @Test
    fun `host-relative windows paths also anchor`() {
        assertEqualsUnix(
            "C:/proj/build/x.zip",
            PackOutputFilter.anchorHostPath("build\\x.zip", "C:/proj"),
        )
    }

    private fun assertEqualsUnix(expected: String, actual: Path?) {
        assertEquals(expected, actual?.toString()?.replace('\\', '/'))
    }
}
