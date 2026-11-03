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
package io.xmake.run.coverage

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path

/**
 * The key under which a source file is registered in [com.intellij.rt.coverage.data.ProjectData]
 * and looked up again when an editor shows coverage. gcov reports the paths the compiler saw,
 * the editor resolves virtual-file paths; both sides go through this normalization so they meet
 * on the same string.
 */
internal object XMakeCoveragePaths {

    fun lookupKey(path: Path): String = normalize(canonical(windowsDriveForm(path)))

    /** A WSL toolchain makes CLion resolve project files through the `wsl.localhost` share,
     *  so an editor's file arrives as `//wsl.localhost/<distro>/mnt/<drive>/...`. The project
     *  itself lives on the drive, and the coverage data is keyed by the drive form, so the
     *  share prefix is folded away here and both forms meet on one key.
     */
    private fun windowsDriveForm(path: Path): Path {
        val text = path.toString()
        val share = Regex("""^[/\\]{2}wsl(?:\$|\.localhost)[/\\][^/\\]+[/\\]mnt[/\\]([a-zA-Z])[/\\](.*)$""")
        val match = share.find(text) ?: return path
        val drive = match.groupValues[1]
        val rest = match.groupValues[2].replace('/', '\\')
        return runCatching { Path.of("$drive:\\$rest") }.getOrDefault(path)
    }

    fun lookupKey(file: File): String = lookupKey(file.toPath())

    /** The key for a path as gcov reported it, resolved against [workingDirectory] when relative. */
    fun lookupKey(reportedPath: String, workingDirectory: Path?): String? {
        val path = runCatching { Path.of(reportedPath) }.getOrNull() ?: return null
        if (path.isAbsolute) return lookupKey(path)
        val base = workingDirectory ?: return null
        return lookupKey(base.resolve(path))
    }

    private fun canonical(path: Path): String =
        runCatching { path.toRealPath().toString() }
            .getOrElse { path.toAbsolutePath().normalize().toString() }

    /** Mirrors the platform's coverage path normalization: system-independent separators and
     *  case-insensitive on Windows. */
    private fun normalize(value: String): String =
        (if (SystemInfo.isWindows) value.lowercase() else value).replace('\\', '/')
}
