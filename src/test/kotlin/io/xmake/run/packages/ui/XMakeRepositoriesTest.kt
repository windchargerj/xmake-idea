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
package io.xmake.run.packages.ui

import io.xmake.run.packages.XMakeRepoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The repositories page groups xmake's listing by origin, not by xmake's raw scope: the
 * global scope mixes the user's own additions with the three repositories xmake loads
 * itself, and reading the bundled one as a user addition is exactly the confusion the
 * grouping exists to prevent. The classification is the page's one piece of derived
 * truth, so it is pinned here.
 */
class XMakeRepositoriesTest {

    @Test
    fun `a local repository is the project's own, even when its name shadows a default`() {
        assertEquals(
            RepoOrigin.PROJECT,
            originOf(XMakeRepoState("xmake-repo", "https://example.com/fork.git", "main", "local")),
        )
    }

    @Test
    fun `xmake's global defaults are their own group`() {
        XMAKE_DEFAULT_REPOSITORIES.forEach { name ->
            assertEquals(
                RepoOrigin.DEFAULT,
                originOf(XMakeRepoState(name, "https://example.com/$name.git", null, "global")),
            )
        }
    }

    @Test
    fun `any other global repository is a user addition`() {
        assertEquals(
            RepoOrigin.GLOBAL,
            originOf(XMakeRepoState("company-repo", "https://git.example.com/packages.git", "master", "global")),
        )
    }

    @Test
    fun `every default carries the explanation that makes its row legible`() {
        XMAKE_DEFAULT_REPOSITORIES.forEach { name ->
            val note = defaultRepoNote(XMakeRepoState(name, "/path", null, "global"))
            assertNotNull("missing explanation for $name", note)
        }
        assertNull(defaultRepoNote(XMakeRepoState("company-repo", "https://example.com", null, "global")))
    }
}
