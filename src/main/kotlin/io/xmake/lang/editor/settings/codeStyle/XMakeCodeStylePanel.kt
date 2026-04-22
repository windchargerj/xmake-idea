package io.xmake.lang.editor.settings.codeStyle

import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.codeStyle.CodeStyleSettings
import io.xmake.lang.file.XMakeLuaFileType
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.editor.highlighting.XMakeLuaSyntaxHighlighter

class XMakeCodeStylePanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings
) : TabbedLanguageCodeStylePanel(XMakeLuaLanguage.INSTANCE, currentSettings, settings) {

    override fun initTabs(settings: CodeStyleSettings) {
        addIndentOptionsTab(settings)
        addSpacesTab(settings)
        addBlankLinesTab(settings)
    }

    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter =
        LexerEditorHighlighter(XMakeLuaSyntaxHighlighter(), scheme)

    override fun getFileType(): FileType = XMakeLuaFileType.INSTANCE
}

