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
 *
 * @author      ruki
 * @file        ToolkitHost.kt
 *
 */
package io.xmake.project.toolkit

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import io.xmake.project.toolkit.ToolkitHostType.*
import io.xmake.utils.extension.ToolkitHostExtension

@Tag("toolkitHost")
data class ToolkitHost(
    @Attribute
    val type: ToolkitHostType = LOCAL,
    @Attribute
    val id: String? = null,
) {
    constructor(type: ToolkitHostType, target: Any) : this(type = type, id = targetId(type, target)) {
        this.target = target
    }

    @Transient
    var target: Any? = null

    internal val endpointIdentity: String
        get() = if (type == LOCAL) LOCAL.name else "$type:${id.orEmpty()}"

    suspend fun loadTarget(project: Project? = null) {
        when (type) {
            LOCAL -> {}
            WSL -> loadWslTarget()
            SSH -> {
                with(HOST_EXTENSIONS.extensions.firstOrNull { it.KEY == "SSH" } ?: return) {
                    loadTargetX(project)
                }
            }
        }
    }

    private fun loadWslTarget() {
        target = WslDistributionManager.getInstance().installedDistributions.find { it.id == id }
    }

    override fun toString(): String {
        return "ToolkitHost(type=$type, id=$id)"
    }

    companion object {
        private val HOST_EXTENSIONS: ExtensionPointName<ToolkitHostExtension> =
            ExtensionPointName("io.xmake.toolkitHostExtension")

        private fun targetId(type: ToolkitHostType, target: Any): String = when (type) {
            LOCAL -> LOCAL.name
            WSL -> (target as? WSLDistribution)
                ?.id
                ?: error("XMake WSL toolkit target must be a WSLDistribution")
            SSH -> HOST_EXTENSIONS.extensions
                .firstOrNull { it.KEY == "SSH" }
                ?.getTargetId(target)
                ?: error("XMake SSH toolkit host extension is not registered")
        }
    }
}
