package io.xmake.lang.analysis.model

import com.intellij.psi.PsiElement

internal data class IdentifierAnalysisResult(
    val semanticKind: IdentifierSemanticKind?,
    val structuralKind: IdentifierStructuralKind?,
    val resolutionStatus: IdentifierResolutionStatus,
    val callableInfo: CallAnalysis?,
    val validationError: ValidationError?,
    val resolvedElement: PsiElement?
) {
    val highlightKind: IdentifierHighlightKind?
        get() = if (validationError == null && !resolutionStatus.isUnresolved) {
            semanticKind ?: structuralKind
        } else {
            null
        }

    val isReferenceCandidate: Boolean
        get() = when {
            callableInfo?.isStableFact == false -> false
            semanticKind != null -> semanticKind.isReferenceCandidate
            structuralKind != null -> true
            resolutionStatus.isUnresolved -> true
            else -> false
        }
}
