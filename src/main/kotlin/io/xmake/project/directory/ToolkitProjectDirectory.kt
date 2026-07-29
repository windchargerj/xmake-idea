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
package io.xmake.project.directory

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType.LOCAL
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.project.toolkit.ToolkitHostType.WSL
import io.xmake.utils.extension.ToolkitHostExtension
import io.xmake.utils.path.WorkingDirectoryResolver

internal suspend fun Toolkit.prepareProjectDirectory(project: Project): String? = when (host.type) {
    LOCAL -> project.basePath
    WSL -> {
        val distribution = host.target as? WSLDistribution ?: return null
        project.basePath?.let { path -> WorkingDirectoryResolver.resolveForWsl(project, path, distribution) }
    }
    SSH -> HOST_EXTENSIONS.extensionList
        .firstOrNull { extension -> extension.KEY == "SSH" }
        ?.prepareProjectDirectory(project, host)
}

private val HOST_EXTENSIONS: ExtensionPointName<ToolkitHostExtension> =
    ExtensionPointName("io.xmake.toolkitHostExtension")
