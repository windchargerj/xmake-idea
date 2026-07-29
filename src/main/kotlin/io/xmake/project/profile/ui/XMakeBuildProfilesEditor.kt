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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.ValidationInfo
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.profile.xmakeBuildProfiles
import javax.swing.JComponent
import javax.swing.tree.DefaultTreeModel

internal class XMakeBuildProfilesEditor(
    private val project: Project,
    private val preselectedProfileId: String?,
) : MasterDetailsComponent() {
    init {
        initTree()
    }

    val component: JComponent = createComponent()

    init {
        reset()
    }

    override fun getDisplayName(): String = "Build Profiles"

    override fun getEmptySelectionString(): String = "Select an XMake build profile"

    override fun isModified(): Boolean =
        super.isModified() || project.xmakeBuildProfiles.getProfiles() != profileEditors().map { it.profile }

    override fun reset() {
        clearChildren()
        project.xmakeBuildProfiles.getProfiles().forEach { profile ->
            myRoot.add(MyNode(createEditor(profile)))
        }
        super.reset()
        preselectProfile(preselectedProfileId)
    }

    fun validateProfiles(): ValidationInfo? {
        val names = profileEditors().map { editor -> editor.displayName.trim() }
        return when {
            names.any(String::isBlank) ->
                ValidationInfo("XMake build profile names must not be blank", tree)
            names.distinct().size != names.size ->
                ValidationInfo("XMake build profile names must be unique", tree)
            else -> null
        }
    }

    @Throws(ConfigurationException::class)
    fun applyChanges() {
        validateProfiles()?.let { validation -> throw ConfigurationException(validation.message) }
        super.apply()
        try {
            project.xmakeBuildProfiles.replaceProfiles(profileEditors().map { editor -> editor.profile })
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.message.orEmpty())
        }
    }

    override fun createActions(fromPopup: Boolean): List<AnAction> = listOf(
        AddProfileAction(),
        MyDeleteAction { selected -> myRoot.childCount > selected.size },
    )

    private fun preselectProfile(profileId: String?) {
        val node = (0 until myRoot.childCount)
            .asSequence()
            .map { index -> myRoot.getChildAt(index) as MyNode }
            .firstOrNull { node -> (node.configurable as XMakeBuildProfileEditor).profile.id == profileId }
            ?: myRoot.getChildAt(0) as? MyNode
        node?.let(::selectNodeInTree)
    }

    private fun createEditor(profile: XMakeBuildProfile): XMakeBuildProfileEditor =
        XMakeBuildProfileEditor(project, profile, TREE_UPDATER)

    private fun profileEditors(): List<XMakeBuildProfileEditor> =
        (0 until myRoot.childCount).map { index ->
            val node = myRoot.getChildAt(index) as MyNode
            node.configurable as XMakeBuildProfileEditor
        }

    private fun addProfile() {
        val profile = XMakeBuildProfile.defaultFor(project, nextProfileName())
        val node = MyNode(createEditor(profile))
        (tree.model as DefaultTreeModel).insertNodeInto(node, myRoot, myRoot.childCount)
        selectNodeInTree(node)
    }

    private fun nextProfileName(): String {
        val names = profileEditors().mapTo(mutableSetOf()) { editor -> editor.displayName.trim() }
        if (PROFILE_NAME !in names) return PROFILE_NAME

        var suffix = 2
        while ("$PROFILE_NAME $suffix" in names) suffix++
        return "$PROFILE_NAME $suffix"
    }

    private inner class AddProfileAction : DumbAwareAction(
        "Add",
        "Add XMake build profile",
        AllIcons.General.Add,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            addProfile()
        }
    }

    private companion object {
        const val PROFILE_NAME = "Profile"
    }
}
