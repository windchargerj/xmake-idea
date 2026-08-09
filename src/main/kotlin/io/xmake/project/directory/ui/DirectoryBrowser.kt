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
 * @file        DirectoryBrowser.kt
 *
 */
package io.xmake.project.directory.ui

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.ui.browseWslPath
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHost
import io.xmake.project.toolkit.ToolkitHostType.LOCAL
import io.xmake.project.toolkit.ToolkitHostType.SSH
import io.xmake.project.toolkit.ToolkitHostType.WSL
import io.xmake.utils.extension.ToolkitHostExtension
import java.awt.event.ActionListener

class DirectoryBrowser(
    val project: Project?,
    private val browseTitle: String = "Working Directory",
    private val browseDescription: String = "Select the working directory",
) : TextFieldWithBrowseButton() {

    private val listeners = mutableSetOf<ActionListener>()

    fun setToolkit(toolkit: Toolkit?) {
        removeBrowseListeners()
        toolkit?.let { addBrowseListener(it.host) }
    }

    private fun createLocalBrowseListener(): ActionListener {
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        return BrowseFolderActionListener(
            this,
            project,
            fileChooserDescriptor
                .withTitle(browseTitle)
                .withDescription(browseDescription),
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
        )
    }

    private fun createWslBrowseListener(target: WSLDistribution): ActionListener {
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(browseTitle)
            .withDescription(browseDescription)
        return ActionListener {
            browseWslPath(
                this,
                target,
                this,
                true,
                fileChooserDescriptor,
            )
        }
    }

    private fun addBrowseListener(host: ToolkitHost) {
        val listener = when (host.type) {
            LOCAL -> {
                createLocalBrowseListener()
            }

            WSL -> {
                val target = host.target as? WSLDistribution ?: return
                createWslBrowseListener(target)
            }

            SSH -> {
                if (host.target == null) return
                val extension = HOST_EXTENSIONS.extensionList.firstOrNull { it.KEY == "SSH" } ?: return
                with(extension) { createBrowseListener(host) }
            }
        }

        addActionListener(listener)
        listeners += listener
        Log.debug("Added directory browser listener for ${host.type}")
    }

    private fun removeBrowseListeners() {
        listeners.forEach(::removeActionListener)
        listeners.clear()
    }

    private companion object {
        private val HOST_EXTENSIONS: ExtensionPointName<ToolkitHostExtension> =
            ExtensionPointName("io.xmake.toolkitHostExtension")
        private val Log = logger<DirectoryBrowser>()
    }
}
