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
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.ValidationInfo
import io.xmake.project.profile.XMakeBuildProfile
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import io.xmake.project.profile.xmakeBuildProfiles
import javax.swing.JComponent
import javax.swing.KeyStroke
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

    override fun getDisplayName(): String = "XMake Profiles"

    override fun getEmptySelectionString(): String = "Select an XMake profile"

    override fun isModified(): Boolean =
        super.isModified() || project.xmakeBuildProfiles.profiles != profileEditors().map { it.profile }

    override fun reset() {
        disposeCurrentEditors()
        clearChildren()
        project.xmakeBuildProfiles.profiles.forEach { profile ->
            val editor = XMakeBuildProfileEditor(project, profile, TREE_UPDATER)
            myRoot.add(MyNode(editor))
        }
        super.reset()
        preselectProfile(preselectedProfileId)
    }

    private fun disposeCurrentEditors() {
        (0 until myRoot.childCount).forEach { index ->
            (myRoot.getChildAt(index) as MyNode).configurable.disposeUIResources()
        }
    }

    fun validateProfiles(): ValidationInfo? {
        val names = profileEditors().map { editor -> editor.displayName.trim() }
        return when {
            names.any(String::isBlank) -> ValidationInfo("XMake build profile names must not be blank", tree)
            names.distinct().size != names.size -> ValidationInfo("XMake build profile names must be unique", tree)
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
        CopyProfileAction(),
        MoveUpProfileAction(),
        MoveDownProfileAction(),
    )

    private fun preselectProfile(profileId: String?) {
        val node = (0 until myRoot.childCount)
            .asSequence()
            .map { index -> myRoot.getChildAt(index) as MyNode }
            .firstOrNull { node -> (node.configurable as XMakeBuildProfileEditor).profile.id == profileId }
            ?: myRoot.getChildAt(0) as? MyNode
        node?.let(::selectNodeInTree)
    }

    private fun profileEditors(): List<XMakeBuildProfileEditor> =
        (0 until myRoot.childCount).map { index ->
            val node = myRoot.getChildAt(index) as MyNode
            node.configurable as XMakeBuildProfileEditor
        }

    private inner class AddProfileAction : DumbAwareAction(
        "Add",
        "Add XMake profile",
        AllIcons.General.Add,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            val names = profileEditors().mapTo(mutableSetOf()) { editor -> editor.displayName.trim() }
            val profile = XMakeBuildProfile.createDefault(project, XMakeBuildProfile.uniqueDefaultName(names))
            val editor = XMakeBuildProfileEditor(project, profile, TREE_UPDATER)
            val node = MyNode(editor)
            (tree.model as DefaultTreeModel).insertNodeInto(node, myRoot, myRoot.childCount)
            selectNodeInTree(node)
        }
    }

    private inner class CopyProfileAction : DumbAwareAction(
        "Copy",
        "Copy XMake profile",
        AllIcons.Actions.Copy,
    ) {
        init {
            registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)),
                tree,
            )
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedProfileNode() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val sourceNode = selectedProfileNode() ?: return
            val sourceEditor = sourceNode.configurable as XMakeBuildProfileEditor
            sourceEditor.apply()

            val usedNames = profileEditors().mapTo(mutableSetOf()) { editor -> editor.displayName.trim() }
            val copiedProfile = sourceEditor.profile.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = XMakeBuildProfile.uniqueName(sourceEditor.displayName.trim(), usedNames),
            )
            val copiedEditor = XMakeBuildProfileEditor(project, copiedProfile, TREE_UPDATER)
            val copiedNode = MyNode(copiedEditor)
            (tree.model as DefaultTreeModel).insertNodeInto(
                copiedNode,
                myRoot,
                myRoot.getIndex(sourceNode) + 1,
            )
            selectNodeInTree(copiedNode)
        }

        private fun selectedProfileNode(): MyNode? =
            tree.selectionPath?.lastPathComponent as? MyNode
    }

    private inner class MoveUpProfileAction : DumbAwareAction(
        "Move Up",
        "Move XMake profile up",
        AllIcons.Actions.MoveUp,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedProfileIndex() > 0
        }

        override fun actionPerformed(event: AnActionEvent) {
            moveSelectedProfile(-1)
        }
    }

    private inner class MoveDownProfileAction : DumbAwareAction(
        "Move Down",
        "Move XMake profile down",
        AllIcons.Actions.MoveDown,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedProfileIndex() in 0 until myRoot.childCount - 1
        }

        override fun actionPerformed(event: AnActionEvent) {
            moveSelectedProfile(1)
        }
    }

    private fun selectedProfileIndex(): Int =
        selectedProfileNode()?.let(myRoot::getIndex) ?: -1

    private fun selectedProfileNode(): MyNode? =
        tree.selectionPath?.lastPathComponent as? MyNode

    private fun moveSelectedProfile(direction: Int) {
        val node = selectedProfileNode() ?: return
        val index = myRoot.getIndex(node)
        val targetIndex = index + direction
        if (index !in 0 until myRoot.childCount || targetIndex !in 0 until myRoot.childCount) return

        val model = tree.model as DefaultTreeModel
        model.removeNodeFromParent(node)
        model.insertNodeInto(node, myRoot, targetIndex)
        selectNodeInTree(node)
    }

}
