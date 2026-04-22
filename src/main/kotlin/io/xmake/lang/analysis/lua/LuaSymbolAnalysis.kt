package io.xmake.lang.analysis.lua

import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.model.IdentifierHighlightKind
import io.xmake.lang.analysis.model.IdentifierResolutionStatus
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.IdentifierStructuralKind

internal data class LuaSymbolAnalysis(
    val semanticKind: IdentifierSemanticKind?,
    val structuralKind: IdentifierStructuralKind?,
    val resolutionStatus: IdentifierResolutionStatus,
    val resolvedElement: PsiElement? = null
) {
    val highlightKind: IdentifierHighlightKind?
        get() = if (resolutionStatus.isUnresolved) null else semanticKind ?: structuralKind

    companion object {
        fun semantic(kind: IdentifierSemanticKind, resolvedElement: PsiElement? = null): LuaSymbolAnalysis =
            LuaSymbolAnalysis(
                semanticKind = kind,
                structuralKind = null,
                resolutionStatus = IdentifierResolutionStatus.RESOLVED,
                resolvedElement = resolvedElement
            )

        fun structural(kind: IdentifierStructuralKind, resolvedElement: PsiElement? = null): LuaSymbolAnalysis =
            LuaSymbolAnalysis(
                semanticKind = null,
                structuralKind = kind,
                resolutionStatus = IdentifierResolutionStatus.RESOLVED,
                resolvedElement = resolvedElement
            )

        fun unresolved(status: IdentifierResolutionStatus): LuaSymbolAnalysis {
            require(status.isUnresolved) { "Expected unresolved status but got $status" }
            return LuaSymbolAnalysis(
                semanticKind = null,
                structuralKind = null,
                resolutionStatus = status
            )
        }

        fun neutral(resolvedElement: PsiElement? = null): LuaSymbolAnalysis =
            LuaSymbolAnalysis(
                semanticKind = null,
                structuralKind = null,
                resolutionStatus = IdentifierResolutionStatus.RESOLVED,
                resolvedElement = resolvedElement
            )
    }
}
