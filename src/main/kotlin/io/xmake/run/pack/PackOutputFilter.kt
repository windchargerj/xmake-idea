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
package io.xmake.run.pack

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.xmake.run.command.RemotePathMapper
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Hyperlinks the `packing <path> ..` lines xmake prints for every artifact it produced
 * (docs/roadmap-designs/packaging.md, F9). The existing [io.xmake.project.console.FileReferenceFilter]
 * cannot do it: it requires a `:line:`/`(line,)` suffix artifact lines never carry, and it
 * anchors relative paths at the IDE's project base path, which is wrong for WSL and SSH
 * toolkits. Here a printed path is anchored at the pack command's working directory (the
 * factory-resolved project directory on the toolkit host), mapped to the IDE-visible path
 * with the toolkit's [RemotePathMapper], and located in the VFS - a path that maps nowhere
 * (e.g. an SSH artifact outside the sync root) stays plain text rather than becoming a
 * dead hyperlink.
 */
class PackOutputFilter(
    private val project: Project,
    private val workingDirectory: String,
    private val remotePathMapper: RemotePathMapper,
) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = PACKING_LINE.find(line) ?: return null
        val path = match.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hostPath = anchorHostPath(path, workingDirectory) ?: return null
        val localPath = remotePathMapper.toLocal(hostPath) ?: return null
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(localPath.toFile())
            ?.takeUnless { it.isDirectory } ?: return null
        // The result spans the whole document, so the match is rebased onto the line's start.
        val lineStart = entireLength - line.length
        return Filter.Result(
            lineStart + match.range.first,
            lineStart + match.range.last + 1,
            OpenFileHyperlinkInfo(project, virtualFile, 0),
        )
    }

    companion object {

        /**
         * One `packing <path> ..` line; the non-greedy path keeps artifacts whose own name
         * carries spaces, and the trailing `..` keeps the match off unrelated prose.
         */
        internal val PACKING_LINE = Regex("""^\s*packing\s+(.+?)\s+\.\.\s*$""")

        /** The printed path of a `packing <path> ..` line, or null for any other text. */
        internal fun parsePackingPath(line: String): String? =
            PACKING_LINE.find(line.trim())?.groups?.get(1)?.value?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Anchors [text] at [workingDirectory] when it is host-relative. Absolute means the
         * toolkit host's forms - unix `/`, a Windows drive letter - not the plugin JVM's
         * platform rules. The result stays a plain `Path.of` on the plugin's JVM even for
         * WSL/SSH paths - the same shape the problem-path mapping in
         * XMakeCommandProcessHandler hands its mappers, which cope with the mangling.
         * Invalid path text answers null; the line stays plain text.
         */
        internal fun anchorHostPath(text: String, workingDirectory: String): Path? {
            val absolute = text.startsWith("/") ||
                    WINDOWS_DRIVE_ANCHOR.containsMatchIn(text) ||
                    File(text).isAbsolute
            val anchored = when {
                absolute -> text
                // A relative path with no anchor would resolve against the IDE process's own
                // working directory, which is never the artifact's; refuse it instead.
                workingDirectory.isBlank() -> return null
                else -> "${workingDirectory.trimEnd('/', '\\')}/$text"
            }
            return try {
                Path.of(anchored)
            } catch (_: InvalidPathException) {
                null
            }
        }

        /** A drive-letter absolute path in either separator form, e.g. `C:\dist` or `C:/dist`. */
        private val WINDOWS_DRIVE_ANCHOR = Regex("^[A-Za-z]:[\\\\/]")
    }
}
