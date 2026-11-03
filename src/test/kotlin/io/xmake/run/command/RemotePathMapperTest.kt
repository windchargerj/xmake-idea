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
package io.xmake.run.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/**
 * The remote-to-local mapping's pure core, without a live project: the SSH root-prefix
 * rule and the shared candidate ladder every analysis consumer walks. The WSL mapper is a
 * one-line delegation to the platform's `WSLDistribution.getWindowsPath` and stays covered
 * by the mapper's own flows (a WSL distribution cannot be built without the IDE).
 */
class RemotePathMapperTest {

    @Test
    fun `an ssh path under the remote root maps into the local sync directory`() {
        val mapper = SshMapper("/home/dev/proj") { "/tmp/sync" }
        val local = mapper.toLocal(Path.of("/home/dev/proj/src/main.cpp"))
        assertEquals("/tmp/sync/src/main.cpp", local.toString().replace('\\', '/'))
    }

    @Test
    fun `a remote root with a trailing slash still matches`() {
        val mapper = SshMapper("/home/dev/proj/") { "/tmp/sync" }
        val local = mapper.toLocal(Path.of("/home/dev/proj/src/main.cpp"))
        assertEquals("/tmp/sync/src/main.cpp", local.toString().replace('\\', '/'))
    }

    @Test
    fun `a path outside the remote root maps to nothing`() {
        val mapper = SshMapper("/home/dev/proj") { "/tmp/sync" }
        assertNull(mapper.toLocal(Path.of("/home/other/project/src/main.cpp")))
    }

    @Test
    fun `a root-prefix lookalike is not a match`() {
        // /home/dev/proj2 shares the prefix characters but not the root directory.
        val mapper = SshMapper("/home/dev/proj") { "/tmp/sync" }
        assertNull(mapper.toLocal(Path.of("/home/dev/proj2/src/main.cpp")))
    }

    @Test
    fun `an unresolvable sync root maps to nothing instead of throwing`() {
        val mapper = SshMapper("/home/dev/proj") { null }
        assertNull(mapper.toLocal(Path.of("/home/dev/proj/src/main.cpp")))
    }

    @Test
    fun `a failing sync-root resolution maps to nothing instead of throwing`() {
        val mapper = SshMapper("/home/dev/proj") { error("resolution crashed") }
        assertNull(mapper.toLocal(Path.of("/home/dev/proj/src/main.cpp")))
    }

    @Test
    fun `the ladder tries the mapping first, then the project root, then the path as it stands`() {
        // A stand-in for a WSL/SSH mapping: it answers a path the file system need not own.
        // The reported path stays relative, so the project-root join means the same thing
        // on every host file system.
        val mapper = RemotePathMapper { Path.of("/mapped", "src", "main.cpp") }
        val candidates = RemotePathMapper.localCandidates(
            mapper,
            Path.of("src", "main.cpp"),
            basePath = "/base",
        )
        assertEquals(
            listOf(
                "/mapped/src/main.cpp",
                "/base/src/main.cpp",
                "src/main.cpp",
            ),
            candidates.map { it.toString().replace('\\', '/') },
        )
    }

    @Test
    fun `a duplicate candidate - the identity mapping's own answer - appears once`() {
        val path = Path.of("/base/src/main.cpp")
        val candidates = RemotePathMapper.localCandidates(
            RemotePathMapper { it }, // the LOCAL toolkit's identity mapping
            path,
            basePath = "/base",
        )
        assertEquals(listOf(path.toString().replace('\\', '/')), candidates.map { it.toString().replace('\\', '/') })
    }

    @Test
    fun `no toolkit and no project root leaves the path as it stands`() {
        val path = Path.of("/standalone/src/main.cpp")
        val candidates = RemotePathMapper.localCandidates(null, path, basePath = null)
        assertEquals(listOf(path.toString().replace('\\', '/')), candidates.map { it.toString().replace('\\', '/') })
    }
}
