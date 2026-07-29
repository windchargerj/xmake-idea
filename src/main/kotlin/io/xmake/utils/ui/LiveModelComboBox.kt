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
package io.xmake.utils.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.ComboBox
import javax.swing.ComboBoxModel
import javax.swing.Timer

private const val LIVE_MODEL_PROPERTY = "ComboBox.jbPopup.supportUpdateModel"
private const val POPUP_REFRESH_DELAY_MS = 50

/**
 * Keeps the JetBrains ComboBox popup in sync with a model mutated in place,
 * with helpers for suppressing selection events and debouncing popup refreshes.
 */
open class LiveModelComboBox<T>(model: ComboBoxModel<T>) : ComboBox<T>(model), Disposable {

    private val popupRefreshTimer = Timer(POPUP_REFRESH_DELAY_MS) {
        notifyModelChanged()
    }.apply {
        isRepeats = false
    }

    protected var suppressSelectionEvents = false
        private set

    init {
        isSwingPopup = false
        putClientProperty(LIVE_MODEL_PROPERTY, true)
    }

    fun notifyModelChanged() {
        try {
            firePropertyChange("model", false, true)
        } catch (error: ClassCastException) {
            Log.debug("Unable to refresh the open ComboBox popup", error)
        }
    }

    protected fun scheduleModelRefresh() {
        popupRefreshTimer.restart()
    }

    protected fun <R> withoutSelectionEvents(action: () -> R): R {
        suppressSelectionEvents = true
        return try {
            action()
        } finally {
            suppressSelectionEvents = false
        }
    }

    override fun dispose() {
        popupRefreshTimer.stop()
    }

    private companion object {
        val Log = logger<LiveModelComboBox<*>>()
    }
}
