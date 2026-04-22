package io.xmake.lang.editor.typing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

class XMakeLuaTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        fileType: FileType
    ): Result {
        if (!isXMakeFile(file)) {
            return Result.CONTINUE
        }

        val settings = CodeInsightSettings.getInstance()
        val offset = editor.caretModel.offset
        val chars = editor.document.charsSequence

        if (c in CLOSING_BRACKETS &&
            settings.AUTOINSERT_PAIR_BRACKET &&
            offset < chars.length &&
            chars[offset] == c
        ) {
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }

        if (c in QUOTES && settings.AUTOINSERT_PAIR_QUOTE) {
            if (offset < chars.length &&
                chars[offset] == c &&
                !isEscaped(chars, offset)
            ) {
                editor.caretModel.moveToOffset(offset + 1)
                return Result.STOP
            }

            if (shouldInsertPairBeforeTyping(chars, offset, c)) {
                editor.document.insertString(offset, "$c$c")
                editor.caretModel.moveToOffset(offset + 1)
                return Result.STOP
            }
        }

        return Result.CONTINUE
    }

    override fun charTyped(
        c: Char,
        project: Project,
        editor: Editor,
        file: PsiFile
    ): Result {
        if (!isXMakeFile(file)) {
            return Result.CONTINUE
        }

        val settings = CodeInsightSettings.getInstance()
        val closing = when {
            c in OPENING_BRACKETS && settings.AUTOINSERT_PAIR_BRACKET -> OPENING_BRACKETS.getValue(c)
            c in QUOTES && settings.AUTOINSERT_PAIR_QUOTE -> c
            else -> return Result.CONTINUE
        }

        val offset = editor.caretModel.offset
        val document = editor.document
        val text = document.charsSequence
        if (text.getOrNull(offset) == closing || !shouldInsertPairAfterTyping(text, offset, c)) {
            return Result.CONTINUE
        }

        document.insertString(offset, closing.toString())
        return Result.STOP
    }

    private fun shouldInsertPairAfterTyping(text: CharSequence, offset: Int, typedChar: Char): Boolean {
        if (offset <= 0 || offset > text.length) {
            return false
        }

        val textString = text.toString()
        val lineStart = lineStartOffset(textString, offset)
        val textBeforeCaret = textString.substring(lineStart, offset)
        if (LuaLexicalTextSupport.isInSingleLineComment(textBeforeCaret) ||
            LuaLexicalTextSupport.isInsideBlockComment(textString, offset)
        ) {
            return false
        }

        if (typedChar in QUOTES && isEscaped(text, offset - 1)) {
            return false
        }

        val nextChar = text.getOrNull(offset)
        return nextChar == null || nextChar.isWhitespace() || nextChar in PAIR_BOUNDARY_CHARS
    }

    private fun shouldInsertPairBeforeTyping(text: CharSequence, offset: Int, typedChar: Char): Boolean {
        if (offset < 0 || offset > text.length) {
            return false
        }

        val textString = text.toString()
        val lineStart = lineStartOffset(textString, offset)
        val textBeforeCaret = textString.substring(lineStart, offset)
        if (LuaLexicalTextSupport.isInSingleLineComment(textBeforeCaret) ||
            LuaLexicalTextSupport.isInsideBlockComment(textString, offset)
        ) {
            return false
        }

        if (typedChar in QUOTES && isEscaped(text, offset)) {
            return false
        }

        val nextChar = text.getOrNull(offset)
        return nextChar == null || nextChar.isWhitespace() || nextChar in PAIR_BOUNDARY_CHARS
    }

    private fun isEscaped(text: CharSequence, index: Int): Boolean {
        if (index <= 0 || index > text.lastIndex) {
            return false
        }

        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashCount++
            cursor--
        }
        return slashCount % 2 == 1
    }

    private fun lineStartOffset(text: String, offset: Int): Int {
        var index = (offset - 1).coerceAtLeast(0)
        while (index > 0 && text[index - 1] != '\n' && text[index - 1] != '\r') {
            index--
        }
        return index
    }

    private fun isXMakeFile(file: PsiFile): Boolean =
        file.language.isKindOf(XMakeLuaLanguage.INSTANCE)

    companion object {
        private val OPENING_BRACKETS = linkedMapOf(
            '(' to ')',
            '[' to ']',
            '{' to '}',
        )

        private val CLOSING_BRACKETS = OPENING_BRACKETS.values.toSet()

        private val QUOTES = setOf('"', '\'')

        private val PAIR_BOUNDARY_CHARS = setOf(
            ')', ']', '}', ',', ';', ':', '\n', '\r'
        )
    }
}
