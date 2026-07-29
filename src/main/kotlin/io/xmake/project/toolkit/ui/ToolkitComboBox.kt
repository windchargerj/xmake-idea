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
 * @file        ToolkitComboBox.kt
 *
 */
package io.xmake.project.toolkit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.validation.DialogValidation
import com.intellij.openapi.ui.validation.transformParameter
import com.intellij.openapi.ui.validation.validationErrorIf
import com.intellij.ui.PopupMenuListenerAdapter
import com.intellij.ui.SortedComboBoxModel
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitListener
import io.xmake.project.toolkit.ToolkitManager
import io.xmake.utils.ui.LiveModelComboBox
import java.awt.event.ItemEvent
import javax.swing.event.PopupMenuEvent
import kotlin.reflect.KMutableProperty0
import kotlin.comparisons.naturalOrder

class ToolkitComboBox(
    private val project: Project?,
    toolkitProperty: KMutableProperty0<Toolkit?>,
) : LiveModelComboBox<ToolkitListItem>(SortedComboBoxModel(naturalOrder())) {

    private val toolkitManager = ToolkitManager.getInstance()
    private val toolkitChangedListeners = mutableListOf<(Toolkit?) -> Unit>()
    private var disposed = false

    private val toolkitModel: SortedComboBoxModel<ToolkitListItem>
        get() = super.getModel() as SortedComboBoxModel<ToolkitListItem>

    var selectedToolkit: Toolkit? by toolkitProperty
        private set

    init {
        maximumRowCount = 30
        renderer = ToolkitComboBoxRenderer(this)
        toolkitModel.add(ToolkitListItem.NoneItem())
        synchronizeToolkits()

        (project?.messageBus ?: ApplicationManager.getApplication().messageBus).connect(this).subscribe(
            ToolkitListener.TOPIC,
            object : ToolkitListener {
                override fun toolkitChanged(sourceProject: Project?, toolkit: Toolkit) {
                    if (!accepts(sourceProject)) return
                    onUiThread { updateToolkit(toolkit) }
                }

                override fun toolkitRemoved(toolkitId: String) {
                    onUiThread(::synchronizeToolkits)
                }

                override fun detectionFinished(sourceProject: Project?) {
                    if (!accepts(sourceProject)) return
                    onUiThread(::synchronizeToolkits)
                }
            },
        )

        addPopupMenuListener(object : PopupMenuListenerAdapter() {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent?) {
                synchronizeToolkits()
                toolkitManager.requestDetection(project)
            }
        })

        addItemListener { event ->
            if (event.stateChange != ItemEvent.SELECTED || suppressSelectionEvents) return@addItemListener

            val toolkit = (event.item as? ToolkitListItem.ToolkitItem)
                ?.toolkit
                ?.let(toolkitManager::registerToolkit)
            if (selectedToolkit === toolkit) return@addItemListener

            selectedToolkit = toolkit
            synchronizeToolkits()
            notifyToolkitChanged()
        }
    }

    override fun addNotify() {
        super.addNotify()
        synchronizeToolkits()
        toolkitManager.requestDetection(project)
    }

    fun addToolkitChangedListener(listener: (Toolkit?) -> Unit) {
        toolkitChangedListeners.add(listener)
    }

    fun selectToolkit(toolkit: Toolkit?) {
        val changed = selectedToolkit !== toolkit
        selectedToolkit = toolkit
        synchronizeToolkits()
        if (changed) notifyToolkitChanged()
    }

    private fun synchronizeToolkits() {
        val knownToolkits = toolkitManager.getKnownToolkits(project)
        val knownToolkitIds = knownToolkits.map(Toolkit::id).toSet()
        val desiredToolkits = linkedMapOf<String, Toolkit>().apply {
            knownToolkits.forEach { toolkit -> put(toolkit.id, toolkit) }
            selectedToolkit?.let { toolkit -> putIfAbsent(toolkit.id, toolkit) }
        }

        val staleItems = toolkitModel.items
            .filterIsInstance<ToolkitListItem.ToolkitItem>()
            .filterNot { item -> item.id in desiredToolkits }
        withoutSelectionEvents {
            staleItems.forEach(toolkitModel::remove)
            desiredToolkits.values.forEach { toolkit ->
                upsertToolkit(toolkit, toolkit.id !in knownToolkitIds)
            }
            selectCurrentToolkit()
        }
    }

    private fun updateToolkit(toolkit: Toolkit) {
        val selectedToolkitUpdated = selectedToolkit?.id == toolkit.id && selectedToolkit !== toolkit
        if (selectedToolkitUpdated) selectedToolkit = toolkit
        synchronizeToolkits()
        if (selectedToolkitUpdated) notifyToolkitChanged()
    }

    private fun upsertToolkit(toolkit: Toolkit, unavailable: Boolean) {
        val nextItem = ToolkitListItem.ToolkitItem(toolkit).apply {
            when {
                toolkit.isRegistered -> asRegistered()
                unavailable -> asInvalid()
            }
        }
        val currentItem = toolkitModel.items
            .filterIsInstance<ToolkitListItem.ToolkitItem>()
            .firstOrNull { item -> item.id == toolkit.id }
        if (currentItem == null) {
            toolkitModel.add(nextItem)
            return
        }
        if (currentItem.toolkit === toolkit && currentItem.hasSamePresentationAs(nextItem)) return

        val wasSelected = toolkitModel.selectedItem === currentItem
        toolkitModel.remove(currentItem)
        toolkitModel.add(nextItem)
        if (wasSelected) toolkitModel.selectedItem = nextItem
    }

    private fun ToolkitListItem.ToolkitItem.hasSamePresentationAs(other: ToolkitListItem.ToolkitItem): Boolean =
        text == other.text &&
            secondaryText == other.secondaryText &&
            tertiaryText == other.tertiaryText &&
            caption == other.caption &&
            isCaptionVisible == other.isCaptionVisible &&
            icon == other.icon

    private fun selectCurrentToolkit() {
        val selectedId = selectedToolkit?.id
        val item = selectedId
            ?.let { id -> toolkitModel.items.firstOrNull { item -> item.id == id } }
            ?: toolkitModel.items.firstOrNull { item -> item is ToolkitListItem.NoneItem }
        if (toolkitModel.selectedItem !== item) toolkitModel.selectedItem = item
    }

    private fun accepts(eventProject: Project?): Boolean =
        eventProject == null || eventProject === project

    private fun onUiThread(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed) action() },
            ModalityState.any(),
        )
    }

    private fun notifyToolkitChanged() {
        toolkitChangedListeners.toList().forEach { listener -> listener(selectedToolkit) }
    }

    override fun dispose() {
        super.dispose()
        disposed = true
        toolkitChangedListeners.clear()
    }

    companion object {
        fun DialogValidation.WithParameter<() -> Toolkit?>.forToolkitComboBox(): DialogValidation.WithParameter<ToolkitComboBox> =
            transformParameter { ::selectedToolkit }

        val CHECK_NON_EMPTY_TOOLKIT: DialogValidation.WithParameter<() -> Toolkit?> =
            validationErrorIf("XMake toolkit is not set!") { toolkit: Toolkit? -> toolkit == null }
    }
}
