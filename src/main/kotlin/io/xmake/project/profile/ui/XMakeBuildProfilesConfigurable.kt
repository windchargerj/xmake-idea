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
package io.xmake.project.profile.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.MasterDetails
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DetailsComponent
import com.intellij.util.messages.MessageBusConnection
import io.xmake.project.profile.XMakeBuildProfileManager
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import javax.swing.JComponent

/**
 * The Settings entry editing the project's XMake build profiles, whichever storage they live in.
 *
 * Implementing [MasterDetails] tells the Settings container that the page already provides the
 * standard master/details chrome; otherwise it adds another margin around the editor and makes
 * the page wider than the other pages.
 */
class XMakeBuildProfilesConfigurable(
    private val project: Project,
) : SearchableConfigurable, Configurable.NoScroll, MasterDetails {
    private var editor: XMakeBuildProfilesEditor? = null
    private var connection: MessageBusConnection? = null

    override fun createComponent(): JComponent {
        if (connection == null) {
            connection = project.messageBus.connect().also { connection ->
                connection.subscribe(
                    XMakeBuildProfileManager.TOPIC,
                    XMakeBuildProfileManager.Listener {
                        val currentEditor = editor ?: return@Listener
                        // The list may change elsewhere while this page is open, for example the
                        // shared storage updated from another project. An untouched page follows
                        // along; a page with local edits keeps them for its own apply.
                        if (!currentEditor.isModified) {
                            currentEditor.reset()
                        }
                    },
                )
            }
        }
        return editorOrCreate().component
    }

    override fun isModified(): Boolean = editor?.isModified == true

    override fun apply() = editorOrCreate().applyChanges()

    override fun reset() {
        editor?.reset()
    }

    override fun disposeUIResources() {
        connection?.disconnect()
        connection = null
        editor?.disposeUIResources()
        editor = null
    }

    override fun getDisplayName(): String = "XMake Profiles"

    override fun getId(): String = "XMakeBuildProfilesSettings"

    override fun initUi() = editorOrCreate().initUi()

    override fun getToolbar(): JComponent = editorOrCreate().toolbar

    override fun getMaster(): JComponent = editorOrCreate().master

    override fun getDetails(): DetailsComponent = editorOrCreate().detailsComponent

    private fun editorOrCreate(): XMakeBuildProfilesEditor = editor ?: XMakeBuildProfilesEditor(
        project,
        project.activeOrSingleXMakeBuildProfile?.id,
    ).also { editor = it }
}
