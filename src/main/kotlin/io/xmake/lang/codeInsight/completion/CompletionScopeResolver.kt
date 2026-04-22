package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

internal object CompletionScopeResolver {
    private val memberSeparators = setOf(".", ":")

    fun inferLookupView(parameters: CompletionParameters): ApiLookupView {
        val position = parameters.originalPosition ?: parameters.position
        val originalFile = CompletionMemberAccessAnalyzer.resolveXMakeFile(
            position.project,
            parameters.originalFile
        )

        return originalFile?.let { file ->
            memberAccessContext(parameters, file)
                ?: if (position.text.isBlank() || shouldUseCaretDomain(parameters)) {
                    contextAt(file, completionOffset(parameters))
                } else {
                    inferLookupView(resolveOriginalAnalysisElement(position, file), parameters.editor)
                }
        } ?: inferLookupView(resolveOriginalAnalysisElement(position, null), parameters.editor)
    }

    fun inferLookupView(position: PsiElement, editor: Editor? = null): ApiLookupView {
        val primary = contextAt(normalizeAnalysisAnchor(position))
        val secondary = editor
            ?.let { position.containingFile as? XMakeLuaFile }
            ?.let { file -> contextAt(file, editor.caretModel.offset) }

        return if (position.text.isBlank() && separatorNearPosition(position) == null && secondary != null) {
            secondary
        } else {
            selectMoreSpecificDomain(primary, secondary)
        }
    }

    private fun memberAccessContext(parameters: CompletionParameters, file: XMakeLuaFile): ApiLookupView? {
        // Heuristic boundary: choose a domain for member completion before PSI catches up with the editor text.
        val access = LuaLexicalTextSupport.detectMemberAccess(
            parameters.editor.document.text,
            parameters.editor.caretModel.offset
        )
        if (access != null) {
            return contextAt(file, access.receiverStartOffset)
        }

        val position = parameters.originalPosition ?: parameters.position
        val mappedLeaf = if (position.containingFile == file) {
            position
        } else {
            nearestAnalysisLeaf(file, position.textOffset) ?: return null
        }
        val separator = separatorNearPosition(mappedLeaf) ?: return null
        val receiver = resolveNearestReceiverBefore(separator) ?: return null
        return contextAt(receiver)
    }

    private fun contextAt(element: PsiElement): ApiLookupView =
        (element as? XMakeLuaIdentifier)?.let(ApiLookupContext::forIdentifier)
            ?: ApiLookupContext.at(element)

    private fun contextAt(file: XMakeLuaFile, offset: Int): ApiLookupView =
        ApiLookupView.fromState(
            XMakeScopeQuery.stateAt(file, offset.coerceIn(0, file.textLength))
        )

    private fun isBlankLineCompletion(parameters: CompletionParameters): Boolean {
        val document = parameters.editor.document
        val offset = completionOffset(parameters).coerceIn(0, document.textLength)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        return document.charsSequence
            .subSequence(lineStart, offset)
            .all { it == ' ' || it == '\t' }
    }

    private fun shouldUseCaretDomain(parameters: CompletionParameters): Boolean {
        if (!isBlankLineCompletion(parameters)) {
            return false
        }

        val position = parameters.originalPosition ?: parameters.position
        val previousCall = previousFunctionCall(position) ?: return false
        val previousName = previousCall.calleeName ?: return false
        return XMakeDescriptionDomainRules.isStructuralEnd(previousName) ||
                (XMakeDescriptionDomainRules.isStructuralEntry(previousName) && previousCall.hasFunctionBodyArgument)
    }

    private fun previousFunctionCall(position: PsiElement): LuaFunctionCall? {
        var leaf = PsiTreeUtil.prevVisibleLeaf(position)
        while (leaf != null) {
            PsiTreeUtil.getParentOfType(leaf, LuaFunctionCall::class.java, false)?.let { return it }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return null
    }

    private fun completionOffset(parameters: CompletionParameters): Int =
        minOf(parameters.offset, parameters.position.textRange.startOffset)

    private fun resolveOriginalAnalysisElement(position: PsiElement, originalFile: XMakeLuaFile?): PsiElement {
        if (originalFile == null || position.containingFile == originalFile) {
            return normalizeAnalysisAnchor(position)
        }
        if (originalFile.textLength <= 0) {
            return originalFile
        }
        val leaf = nearestAnalysisLeaf(originalFile, position.textOffset) ?: originalFile
        return normalizeAnalysisAnchor(leaf)
    }

    private fun nearestAnalysisLeaf(file: XMakeLuaFile, offset: Int): PsiElement? {
        if (file.textLength <= 0) {
            return null
        }

        // Heuristic boundary: copied completion PSI offsets can drift slightly from the original file.
        val clamped = offset.coerceIn(0, file.textLength - 1)
        file.findElementAt(clamped)?.let { return it }

        for (distance in 1..64) {
            val left = clamped - distance
            if (left >= 0) {
                file.findElementAt(left)?.let { return it }
            }

            val right = clamped + distance
            if (right < file.textLength) {
                file.findElementAt(right)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeAnalysisAnchor(position: PsiElement): PsiElement {
        val containingFile = position.containingFile ?: return position
        identifierAtOrParent(position)?.let { return it }

        if (position.text in memberSeparators) {
            resolveReceiverBefore(position)?.let { return it }
        }

        PsiTreeUtil.prevVisibleLeaf(position)
            ?.takeIf { it.text in memberSeparators }
            ?.let(::resolveReceiverBefore)
            ?.let { return it }

        if (!position.text.isBlank()) {
            return position
        }

        return PsiTreeUtil.prevVisibleLeaf(position)
            ?: PsiTreeUtil.nextVisibleLeaf(position)
            ?: containingFile
    }

    private fun resolveReceiverBefore(separator: PsiElement): PsiElement? {
        val receiverLeaf = PsiTreeUtil.prevVisibleLeaf(separator) ?: return null
        return identifierAtOrParent(receiverLeaf)
    }

    private fun separatorNearPosition(position: PsiElement): PsiElement? {
        if (position.text in memberSeparators) {
            return position
        }
        val previousLeaf = PsiTreeUtil.prevVisibleLeaf(position)
        return previousLeaf?.takeIf { it.text in memberSeparators }
    }

    private fun resolveNearestReceiverBefore(separator: PsiElement): XMakeLuaIdentifier? {
        var leaf = PsiTreeUtil.prevVisibleLeaf(separator)
        while (leaf != null) {
            identifierAtOrParent(leaf)?.let { return it }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return null
    }

    private fun identifierAtOrParent(element: PsiElement): XMakeLuaIdentifier? =
        (element as? XMakeLuaIdentifier)
            ?: PsiTreeUtil.getParentOfType(element, XMakeLuaIdentifier::class.java, false)

    private fun selectMoreSpecificDomain(
        primary: ApiLookupView,
        secondary: ApiLookupView?
    ): ApiLookupView {
        if (secondary == null) {
            return primary
        }
        return if (domainSpecificity(secondary.domain) > domainSpecificity(primary.domain)) secondary else primary
    }

    private fun domainSpecificity(domain: XMakeDomain): Int = when (domain) {
        is XMakeDomain.Description -> 0
        is XMakeDomain.Configuration -> 1
        is XMakeDomain.Script -> 2
    }
}
