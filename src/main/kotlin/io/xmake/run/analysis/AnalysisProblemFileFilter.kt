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
package io.xmake.run.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile

/**
 * The files the analysis findings may mark. Without this filter the problem solver
 * silently ignores reports for files whose language another plugin owns - the platform
 * gates marking on exactly this condition - so the plugin states its claim: the C and
 * C++ sources an xmake project compiles.
 */
internal class AnalysisProblemFileFilter(private val project: Project) : Condition<VirtualFile> {

    override fun value(file: VirtualFile): Boolean {
        if (file.fileType.isBinary) return false
        val basePath = project.basePath ?: return false
        val path = file.path
        if (!path.startsWith(basePath)) return false
        return file.extension?.lowercase() in ANALYZED_EXTENSIONS
    }

    private companion object {
        private val ANALYZED_EXTENSIONS = setOf(
            "c", "cc", "cpp", "cxx", "c++",
            "h", "hh", "hpp", "hxx", "h++",
            "inl", "inc", "m", "mm", "cu", "cuh",
        )
    }
}
