package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.XMakeState
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.XMakeLuaLanguage
import kotlin.math.max

object XMakeCompletionInsertHandlers {

    private data class InsertPlan(
        val text: String,
        val caretOffsetInText: Int
    )

    private val callInsertHandler = ParenthesesInsertHandler.getInstance(true)

    fun handlerFor(api: ApiModel): InsertHandler<LookupElement> =
        when {
            XMakeDescriptionDomainRules.isStructuralEntry(api.name) ->
                StructuralEntryInsertHandler(api.name)

            XMakeDescriptionDomainRules.isStructuralEnd(api.name) ->
                StructuralEndInsertHandler(api.name)

            else -> callInsertHandler
        }

    private class StructuralEntryInsertHandler(
        private val keyword: String
    ) : InsertHandler<LookupElement> {

        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val lineStartOffset = lineStartOffset(document, context.startOffset)
            val linePrefixBlank = isLinePrefixBlank(document, context.startOffset)
            val replaceStart = if (linePrefixBlank) lineStartOffset else context.startOffset
            val state = stateAt(context.file, replaceStart)
            val insertPlan = buildEntryPlan(
                keyword = keyword,
                file = context.file,
                state = state,
                closeCurrentScope = linePrefixBlank && state.domain is XMakeDomain.Configuration,
                asBlock = linePrefixBlank
            )

            document.replaceString(replaceStart, context.tailOffset, insertPlan.text)
            context.editor.caretModel.moveToOffset(replaceStart + insertPlan.caretOffsetInText)
            context.commitDocument()
        }
    }

    private class StructuralEndInsertHandler(
        private val keyword: String
    ) : InsertHandler<LookupElement> {

        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            val linePrefixBlank = isLinePrefixBlank(document, context.startOffset)
            val replaceStart = if (linePrefixBlank) lineStartOffset(document, context.startOffset) else context.startOffset
            val state = stateAt(context.file, replaceStart)
            val indentDepth = structuralEndIndentDepth(keyword, state)
            val text = buildString {
                if (linePrefixBlank) {
                    append(indentString(context.file, indentDepth))
                }
                append(keyword)
                append("()")
            }

            document.replaceString(replaceStart, context.tailOffset, text)
            context.editor.caretModel.moveToOffset(replaceStart + text.length)
            context.commitDocument()
        }
    }

    private fun buildEntryPlan(
        keyword: String,
        file: PsiFile,
        state: XMakeState,
        closeCurrentScope: Boolean,
        asBlock: Boolean
    ): InsertPlan {
        val baseIndentDepth = namespaceDepth(state.root)
        val baseIndent = indentString(file, baseIndentDepth)
        val bodyIndent = indentString(file, baseIndentDepth + 1)
        val currentScopeEnd = (state.domain as? XMakeDomain.Configuration)?.type?.toEndKeyword()
        val entryText = "$keyword(\"\")"
        val endKeyword = closingKeywordForEntry(keyword)

        if (!asBlock) {
            return InsertPlan(
                text = entryText,
                caretOffsetInText = keyword.length + 2
            )
        }

        val prefix = buildString {
            if (closeCurrentScope && currentScopeEnd != null) {
                append(baseIndent)
                append(currentScopeEnd)
                append("()")
                append("\n\n")
            }
        }

        val text = buildString {
            append(prefix)
            append(baseIndent)
            append(entryText)
            append('\n')
            append(bodyIndent)
            append('\n')
            append(baseIndent)
            append(endKeyword)
            append("()")
        }

        return InsertPlan(
            text = text,
            caretOffsetInText = prefix.length + baseIndent.length + keyword.length + 2
        )
    }

    private fun closingKeywordForEntry(keyword: String): String =
        when {
            XMakeDescriptionDomainRules.isNamespaceEntry(keyword) -> XMakeDescriptionDomainRules.NAMESPACE_END_KEYWORD
            else -> "${keyword}_end"
        }

    private fun structuralEndIndentDepth(keyword: String, state: XMakeState): Int =
        when {
            XMakeDescriptionDomainRules.isNamespaceEnd(keyword) ->
                max(namespaceDepth(state.root) - 1, 0)

            state.domain is XMakeDomain.Configuration ->
                namespaceDepth(state.root)

            else -> namespaceDepth(state.root)
        }

    @Suppress("DEPRECATION")
    private fun indentString(file: PsiFile, depth: Int): String {
        if (depth <= 0) {
            return ""
        }

        val settings = CodeStyleSettingsManager.getSettings(file.project)
        val indentOptions = settings
            .getCommonSettings(XMakeLuaLanguage.INSTANCE)
            .indentOptions
            ?: settings.getIndentOptions(file.fileType)

        return if (indentOptions.USE_TAB_CHARACTER) {
            "\t".repeat(depth)
        } else {
            " ".repeat(depth * max(indentOptions.INDENT_SIZE, 1))
        }
    }

    private fun namespaceDepth(root: XMakeRoot): Int =
        when (root) {
            is XMakeRoot.Global -> 0
            is XMakeRoot.Namespace -> 1 + namespaceDepth(root.parent)
        }

    private fun stateAt(file: PsiFile, offset: Int): XMakeState =
        XMakeScopeQuery.stateAt(file, offset.coerceIn(0, file.textLength))

    private fun lineStartOffset(document: Document, offset: Int): Int =
        document.getLineStartOffset(document.getLineNumber(offset.coerceIn(0, document.textLength)))

    private fun isLinePrefixBlank(document: Document, offset: Int): Boolean {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineStart = lineStartOffset(document, safeOffset)
        return document.charsSequence
            .subSequence(lineStart, safeOffset)
            .all { it == ' ' || it == '\t' }
    }

}
