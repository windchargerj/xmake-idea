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
package io.xmake.project.profile

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.project.Project
import com.intellij.util.IncorrectOperationException
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.project.toolkit.ToolkitManager
import io.xmake.utils.path.WorkingDirectoryResolver
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

/** Persisted project build inputs shared by Build, Run, and Debug. */
data class XMakeBuildProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Default",
    var toolkitId: String? = null,
    var platform: String = "default",
    var architecture: String = "default",
    var toolchain: String = "default",
    var mode: String = "release",
    var workingDirectory: String = "",
    var buildDirectory: String = "",
    var androidNdkDirectory: String = "",
    var verbose: Boolean = false,
    var additionalConfiguration: String = "",
) {
    internal fun resolveToolkit(project: Project): Toolkit? =
        toolkitId?.let { id -> ToolkitManager.getInstance().resolveRegisteredToolkit(id, project) }

    internal fun resolveWorkingDirectory(project: Project, toolkit: Toolkit): String {
        if (toolkit.path.isBlank()) {
            throw RuntimeConfigurationError("XMake toolkit path is not set")
        }
        if (toolkit.isOnRemote && toolkit.host.target == null) {
            throw RuntimeConfigurationError("XMake ${toolkit.host.type} toolkit host is not available")
        }
        if (workingDirectory.isBlank()) {
            throw RuntimeConfigurationError("Working directory is not set")
        }
        val resolvedWorkingDirectory = try {
            when (toolkit.host.type) {
                ToolkitHostType.LOCAL ->
                    WorkingDirectoryResolver.resolve(project, workingDirectory, validation = true)

                else -> WorkingDirectoryResolver.resolve(project, workingDirectory, toolkit)
            }
        } catch (error: IncorrectOperationException) {
            throw RuntimeConfigurationError(error.message ?: "Working directory contains invalid macros")
        }
        if (toolkit.host.type != ToolkitHostType.LOCAL) {
            if (!resolvedWorkingDirectory.startsWith('/')) {
                throw RuntimeConfigurationError(
                    "XMake ${toolkit.host.type} working directory must be an absolute host path",
                )
            }
            return resolvedWorkingDirectory
        }

        val path = try {
            Path.of(resolvedWorkingDirectory)
        } catch (_: InvalidPathException) {
            throw RuntimeConfigurationError("Working directory is invalid: $resolvedWorkingDirectory")
        }
        if (!Files.isDirectory(path)) {
            throw RuntimeConfigurationError("Working directory does not exist: $resolvedWorkingDirectory")
        }
        return resolvedWorkingDirectory
    }

    companion object {
        internal fun defaultFor(project: Project, name: String = "Default"): XMakeBuildProfile {
            val toolkit = ToolkitManager.getInstance().getRegisteredToolkits(project).firstOrNull()
            return XMakeBuildProfile(
                name = name,
                toolkitId = toolkit?.id,
                workingDirectory = if (toolkit?.host?.type == ToolkitHostType.SSH) {
                    ""
                } else {
                    project.basePath.orEmpty()
                },
            )
        }

        internal fun isValidId(id: String): Boolean = PROFILE_ID.matches(id)

        private val PROFILE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
