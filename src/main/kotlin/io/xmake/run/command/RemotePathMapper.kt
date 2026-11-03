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

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import io.xmake.project.directory.xmakeProjectDirectories
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import java.nio.file.Path

fun interface RemotePathMapper {
    fun toLocal(remotePath: Path): Path?

    companion object {
        fun forToolkit(toolkit: Toolkit, workingDirectory: String, project: Project): RemotePathMapper = when (toolkit.host.type) {
            ToolkitHostType.LOCAL -> RemotePathMapper { it }

            ToolkitHostType.WSL -> WslMapper(toolkit.host.wslDistribution)

            ToolkitHostType.SSH -> SshMapper.forProject(workingDirectory, project)
        }

        /**
         * The candidate local paths one reported frame path may name, in the order the
         * analysis consumers try them: the toolkit's own mapping, the join against the
         * project root, then the path as it stands. Every consumer used to hand-write this
         * ladder - two of them byte-identical, one in a different order and with different
         * existence semantics - so the ladder lives here once and a change to it is one
         * edit; the consumers differ only in how they test a candidate (filesystem
         * existence for navigation and problem reporting, plain equality for editor marks).
         */
        fun localCandidates(mapper: RemotePathMapper?, remotePath: Path, project: Project): List<Path> =
            localCandidates(mapper, remotePath, project.basePath)

        /** The ladder against a plain project root - the seam the unit tests run through. */
        internal fun localCandidates(mapper: RemotePathMapper?, remotePath: Path, basePath: String?): List<Path> {
            val mapped = mapper?.toLocal(remotePath)
            val candidates = buildList {
                mapped?.let(::add)
                val text = FileUtilRt.toSystemIndependentName(remotePath.toString())
                basePath?.let { root -> add(Path.of(root).resolve(text).normalize()) }
                add(remotePath)
            }
            return candidates.distinct()
        }
    }
}

private class WslMapper(private val distribution: WSLDistribution?) : RemotePathMapper {
    override fun toLocal(remotePath: Path): Path? =
        distribution?.getWindowsPath(remotePath.toString())?.let(Path::of)
}

/** Maps a remote frame path into the local sync directory: only paths under the run's
 *  remote working directory map - everything else has no local counterpart. The sync root
 *  comes through [resolveSyncRoot], which the project wiring provides and tests replace. */
internal class SshMapper internal constructor(
    private val remoteRoot: String,
    private val resolveSyncRoot: () -> String?,
) : RemotePathMapper {

    override fun toLocal(remotePath: Path): Path? {
        val text = FileUtilRt.toSystemIndependentName(remotePath.toString())
        val rootPrefix = "${remoteRoot.trimEnd('/')}/"
        if (!text.startsWith(rootPrefix)) return null
        val localRoot = runCatching { resolveSyncRoot() }.getOrNull()
            ?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        return runCatching { localRoot.resolve(text.removePrefix(rootPrefix)).normalize() }.getOrNull()
    }

    companion object {

        /** The production wiring: the sync root from the project's directory manager, itself
         *  already failure-tolerant - the wrap keeps the mapper total all the same. */
        internal fun forProject(remoteRoot: String, project: Project): SshMapper =
            SshMapper(remoteRoot) {
                runCatching { project.xmakeProjectDirectories.resolveLocalSyncDirectory() }.getOrNull()
            }
    }
}
