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
 * @file        XMakeInfoActivity.kt
 *
 */
package io.xmake.utils.info

import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.xmake.project.profile.XMakeBuildProfileManager
import io.xmake.run.target.activeXMakeBuildProfile
import io.xmake.run.target.xmakeBuildProfile
import io.xmake.utils.SystemUtils

class XMakeInfoActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!SystemUtils.isXMakeProject(project)) return

        val manager = XMakeInfoManager.getInstance(project)
        project.activeXMakeBuildProfile().let { profile ->
            manager.preloadBuildProfileInfo(profile)
            manager.refreshSyntaxInfo(profile)
        }

        project.messageBus.connect().apply {
            subscribe(ExecutionTargetManager.TOPIC, ExecutionTargetListener { target ->
                project.xmakeBuildProfile(target).let { profile ->
                    manager.preloadBuildProfileInfo(profile)
                    manager.refreshSyntaxInfo(profile)
                }
            })
            subscribe(XMakeBuildProfileManager.TOPIC, XMakeBuildProfileManager.Listener {
                project.activeXMakeBuildProfile().let { profile ->
                    manager.preloadBuildProfileInfo(profile)
                    manager.refreshSyntaxInfo(profile)
                }
            })
        }
    }
}
