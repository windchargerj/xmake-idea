package io.xmake.lang.editor.typing

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.lang.ASTNode
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaFunctionBody
import io.xmake.lang.syntax.psi.lua.LuaStatement
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeLuaEnterBetweenBlockDelimitersHandler : EnterHandlerDelegate {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?
    ): EnterHandlerDelegate.Result {
        if (!file.language.isKindOf(XMakeLuaLanguage.INSTANCE) ||
            !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER
        ) {
            return EnterHandlerDelegate.Result.Continue
        }

        val document = editor.document
        val psiDocumentManager = PsiDocumentManager.getInstance(file.project)
        psiDocumentManager.commitDocument(document)

        val offset = caretOffset.get()
        val boundary = blockBoundaryBeforeCaretAt(file, offset, document.trimTrailingHorizontalWhitespace(offset))
            ?: return EnterHandlerDelegate.Result.Continue
        val baseIndent = document.lineIndentAt(boundary.openStartOffset)
        val bodyIndent = baseIndent + indentUnit(file)
        val leadingLineBreak = document.hasNonWhitespaceBeforeOffsetOnLine(boundary.replaceStartOffset)
        val replacement = buildString {
            if (leadingLineBreak) {
                append('\n')
            }
            append(bodyIndent)
            append('\n')
            append(baseIndent)
        }

        document.replaceString(boundary.replaceStartOffset, boundary.closeStartOffset, replacement)
        val bodyOffset = boundary.replaceStartOffset +
            (if (leadingLineBreak) 1 else 0) +
            bodyIndent.length
        psiDocumentManager.commitDocument(document)

        editor.caretModel.moveToOffset(bodyOffset)
        caretOffset.set(bodyOffset)
        caretAdvance.set(0)
        return EnterHandlerDelegate.Result.Stop
    }

    private fun blockBoundaryBeforeCaretAt(file: PsiFile, offset: Int, replaceStartOffset: Int): LuaBlockBoundary? {
        val closing = nextNonWhitespaceLeaf(file, offset) ?: return null
        val block = blockBeforeClosingBoundary(closing) ?: return null
        val container = block.parent ?: return null
        val opening = openingBoundaryBefore(block) ?: return null

        if (!isOpeningBoundary(opening, container) || !isClosingBoundary(closing, container, opening)) {
            return null
        }

        return LuaBlockBoundary(
            opening.textRange.startOffset,
            replaceStartOffset,
            closing.textRange.startOffset
        )
    }

    private fun blockBeforeClosingBoundary(closing: PsiElement): LuaBlock? {
        val functionBody = PsiTreeUtil.getParentOfType(closing, LuaFunctionBody::class.java, false)
        if (functionBody != null && closing.node.elementType == tokenType(LuaLexer.END)) {
            return functionBody.children
                .filterIsInstance<LuaBlock>()
                .lastOrNull { it.textRange.endOffset <= closing.textRange.startOffset }
        }

        val statement = PsiTreeUtil.getParentOfType(closing, LuaStatement::class.java, false)
            ?: return null

        return statement.children
            .filterIsInstance<LuaBlock>()
            .lastOrNull { it.textRange.endOffset <= closing.textRange.startOffset }
    }

    private fun openingBoundaryBefore(block: LuaBlock): PsiElement? =
        generateSequence(block.node.treePrev) { it.treePrev }
            .mapNotNull(::lastNonWhitespaceLeaf)
            .firstOrNull()

    private fun isOpeningBoundary(opening: PsiElement, container: PsiElement): Boolean =
        when (container) {
            is LuaFunctionBody -> FUNCTION_BODY_OPENING_TOKENS.contains(opening.node.elementType)
            is LuaStatement -> LUA_BLOCK_OPENING_TOKENS.contains(opening.node.elementType)
            else -> false
        }

    private fun isClosingBoundary(closing: PsiElement, container: PsiElement, opening: PsiElement): Boolean =
        when (container) {
            is LuaFunctionBody -> closing.node.elementType == tokenType(LuaLexer.END)
            is LuaStatement -> when (opening.node.elementType) {
                tokenType(LuaLexer.REPEAT) -> closing.node.elementType == tokenType(LuaLexer.UNTIL)
                tokenType(LuaLexer.THEN) -> LUA_THEN_BLOCK_BOUNDARY_TOKENS.contains(closing.node.elementType)
                tokenType(LuaLexer.ELSE) -> closing.node.elementType == tokenType(LuaLexer.END)
                tokenType(LuaLexer.DO) -> closing.node.elementType == tokenType(LuaLexer.END)
                else -> false
            }

            else -> false
        }

    private fun nextNonWhitespaceLeaf(file: PsiFile, offset: Int): PsiElement? {
        if (offset >= file.textLength) {
            return null
        }

        var leaf = file.findElementAt(offset) ?: return null
        while (leaf is PsiWhiteSpace) {
            leaf = PsiTreeUtil.nextLeaf(leaf) ?: return null
        }
        return leaf
    }

    private fun lastNonWhitespaceLeaf(node: ASTNode): PsiElement? {
        if (node.elementType == TokenType.WHITE_SPACE || node.psi is PsiComment) {
            return null
        }

        var child = node.lastChildNode
        while (child != null) {
            lastNonWhitespaceLeaf(child)?.let { return it }
            child = child.treePrev
        }

        return node.psi.takeUnless { it is PsiWhiteSpace || it is PsiComment }
    }

    private fun Document.lineIndentAt(offset: Int): String {
        val line = getLineNumber(offset)
        val lineStart = getLineStartOffset(line)
        val lineEnd = getLineEndOffset(line)
        val text = charsSequence
        var index = lineStart
        while (index < lineEnd && (text[index] == ' ' || text[index] == '\t')) {
            index++
        }
        return text.subSequence(lineStart, index).toString()
    }

    private fun Document.hasNonWhitespaceBeforeOffsetOnLine(offset: Int): Boolean {
        val lineStart = getLineStartOffset(getLineNumber(offset))
        val text = charsSequence
        var index = lineStart
        while (index < offset) {
            if (text[index] != ' ' && text[index] != '\t') {
                return true
            }
            index++
        }
        return false
    }

    private fun Document.trimTrailingHorizontalWhitespace(offset: Int): Int {
        var index = offset
        val text = charsSequence
        while (index > 0 && (text[index - 1] == ' ' || text[index - 1] == '\t')) {
            index--
        }
        return index
    }

    private fun indentUnit(file: PsiFile): String {
        val indentOptions = CodeStyle.getSettings(file)
            .getCommonSettings(file.language)
            .indentOptions
        return if (indentOptions?.USE_TAB_CHARACTER == true) {
            "\t"
        } else {
            " ".repeat(CodeStyle.getIndentSize(file).coerceAtLeast(1))
        }
    }

    private data class LuaBlockBoundary(
        val openStartOffset: Int,
        val replaceStartOffset: Int,
        val closeStartOffset: Int
    )

    companion object {
        private fun tokenSet(vararg tokens: Int): TokenSet =
            PSIElementTypeFactory.createTokenSet(XMakeLuaLanguage.INSTANCE, *tokens)

        private fun tokenType(token: Int) =
            PSIElementTypeFactory.getTokenIElementTypes(XMakeLuaLanguage.INSTANCE)[token]

        private val FUNCTION_BODY_OPENING_TOKENS = tokenSet(LuaLexer.CP)
        private val LUA_BLOCK_OPENING_TOKENS = tokenSet(
            LuaLexer.DO,
            LuaLexer.THEN,
            LuaLexer.ELSE,
            LuaLexer.REPEAT
        )
        private val LUA_THEN_BLOCK_BOUNDARY_TOKENS = tokenSet(
            LuaLexer.ELSEIF,
            LuaLexer.ELSE,
            LuaLexer.END
        )
    }
}
