package io.xmake.lang.editor.settings.codeStyle

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings

class XMakeCodeStyleConfigurable(
    settings: CodeStyleSettings,
    cloneSettings: CodeStyleSettings
) : CodeStyleAbstractConfigurable(settings, cloneSettings, "XMake Lua") {

    override fun createPanel(settings: CodeStyleSettings) = XMakeCodeStylePanel(currentSettings, settings)

    override fun getHelpTopic(): String = "reference.settingsdialog.codestyle.xmake"
}

