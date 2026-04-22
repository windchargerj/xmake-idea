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
 * @author      ruki, windchargerj
 * @file        XMakeInfoManager.kt
 *
 */
package io.xmake.utils.info

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.xmake.lang.declarations.source.ApiService
import io.xmake.project.toolkit.Toolkit
import io.xmake.utils.execute.createProcess
import io.xmake.utils.execute.runProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Service(Service.Level.PROJECT)
class XMakeInfoManager(val project: Project, private val scope: CoroutineScope) {

    val xmakeInfo: XMakeInfo = XMakeInfo()

    private val platformKeys = listOf("architectures", "buildmodes", "platforms", "toolchains")


    fun refreshXMakeData(toolkit: Toolkit?) {
        toolkit ?: return
        scope.launch {
            coroutineScope {
                launch { refreshPlatforms(toolkit) }
                launch { refreshTargets(toolkit) }
                launch { refreshApis(toolkit) }
            }
        }
    }

    fun refreshPlatforms(toolkit: Toolkit?) {
        toolkit ?: return
        scope.launch {
            coroutineScope {
                platformKeys.forEach { key ->
                    launch { refreshItem(toolkit, key) }
                }
            }
            notifyUpdated()
        }
    }

    fun refreshTargets(toolkit: Toolkit?, workingDir: String? = projectDir) {
        toolkit ?: return
        scope.launch {
            val result = runXMakeShow(toolkit, "targets", workingDir)
            xmakeInfo.targets = xmakeInfo.parseTargets(result)
            notifyUpdated()
        }
    }

    fun refreshApis(toolkit: Toolkit?) {
        toolkit ?: return
        scope.launch {
            xmakeInfo.apis = xmakeInfo.parseApis(runXMakeShow(toolkit, "apis", projectDir))
            ApiService.getInstance(project).reload()
            notifyUpdated()
        }
    }

    fun forceRefresh(toolkit: Toolkit?) = refreshXMakeData(toolkit)


    private suspend fun refreshItem(toolkit: Toolkit, key: String) {
        val result = runXMakeShow(toolkit, key, projectDir)
        when (key) {
            "architectures" -> xmakeInfo.architectures = xmakeInfo.parseArchitectures(result)
            "buildmodes" -> xmakeInfo.buildModes = xmakeInfo.parseBuildModes(result)
            "platforms" -> xmakeInfo.platforms = xmakeInfo.parsePlatforms(result)
            "toolchains" -> xmakeInfo.toolchains = xmakeInfo.parseToolchains(result)
        }
    }

    private val projectDir: String?
        get() = project.basePath

    private fun notifyUpdated() {
        project.messageBus.syncPublisher(XMAKE_INFO_TOPIC).onXMakeInfoUpdated(xmakeInfo)
    }

    private suspend fun runXMakeShow(toolkit: Toolkit, key: String, dir: String?): String {
        val cmd = GeneralCommandLine("xmake show -l $key --json".split(" ")).apply {
            dir?.let { withWorkDirectory(File(it)) }
            withEnvironment("XMAKE_SKIP_HISTORY", "1")
            withEnvironment("XMAKE_ROOT", "y")
            withEnvironment("XMAKE_COLOR_TERM", "nocolor")
        }
        return runProcess(cmd.createProcess(toolkit)).first.getOrDefault("")
    }


    interface XMakeInfoListener {
        fun onXMakeInfoUpdated(xmakeInfo: XMakeInfo)
    }

    companion object {
        val Log = logger<XMakeInfoManager>()
        val XMAKE_INFO_TOPIC = Topic.create("XMake Info Updated", XMakeInfoListener::class.java)

        fun getInstance(project: Project): XMakeInfoManager =
            project.serviceOrNull() ?: throw IllegalStateException()
    }
}
