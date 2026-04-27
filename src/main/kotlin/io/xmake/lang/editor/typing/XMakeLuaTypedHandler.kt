package io.xmake.lang.editor.typing

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

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
                !LuaLexicalTextSupport.isEscaped(chars, offset)
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
        val closing = when (c) {
            in OPENING_BRACKETS -> if (settings.AUTOINSERT_PAIR_BRACKET) OPENING_BRACKETS.getValue(c) else return Result.CONTINUE
            in QUOTES -> if (settings.AUTOINSERT_PAIR_QUOTE) c else return Result.CONTINUE
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

        if (typedChar in QUOTES && LuaLexicalTextSupport.isEscaped(text, offset - 1)) {
            return false
        }

        if (typedChar in QUOTES && isInsideOrdinaryString(text, offset - 1)) {
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

        if (typedChar in QUOTES && LuaLexicalTextSupport.isEscaped(text, offset)) {
            return false
        }

        if (typedChar in QUOTES && isInsideOrdinaryString(text, offset)) {
            return false
        }

        val nextChar = text.getOrNull(offset)
        return nextChar == null || nextChar.isWhitespace() || nextChar in PAIR_BOUNDARY_CHARS
    }

    private fun isInsideOrdinaryString(text: CharSequence, offset: Int): Boolean {
        if (offset < 0 || offset > text.length) {
            return false
        }

        var quote: Char? = null
        var index = lineStartOffset(text.toString(), offset)
        while (index < offset) {
            val current = text[index]
            if (quote != null) {
                if (current == quote && !LuaLexicalTextSupport.isEscaped(text, index)) {
                    quote = null
                }
            } else if (current in QUOTES && !LuaLexicalTextSupport.isEscaped(text, index)) {
                quote = current
            }
            index++
        }
        return quote != null
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
}
