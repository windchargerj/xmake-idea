package io.xmake.lang.analysis.model

import com.intellij.psi.PsiElement

internal enum class CallableIntentState {
    DIRECT_CALL,
    EDITOR_RECOVERY_CALL_HEAD
}

internal enum class CallAnalysisSource {
    STABLE_PSI,
    EDITOR_RECOVERY
}

internal data class CallAnalysis(
    val semanticKind: IdentifierSemanticKind?,
    val structuralKind: IdentifierStructuralKind?,
    val intent: CallableIntentState,
    val resolvedElement: PsiElement? = null,
    val source: CallAnalysisSource = CallAnalysisSource.STABLE_PSI
) {
    init {
        check(semanticKind != null || structuralKind != null) {
            "CallAnalysis requires either a semantic kind or a structural kind"
        }
    }

    val bypassesApiAvailabilityValidation: Boolean
        get() = source == CallAnalysisSource.STABLE_PSI && semanticKind in setOf(
            IdentifierSemanticKind.FUNCTION_CALL,
            IdentifierSemanticKind.MODULE_CALL,
            IdentifierSemanticKind.INSTANCE_METHOD
        )

    val isStableFact: Boolean
        get() = source == CallAnalysisSource.STABLE_PSI

    companion object {
        fun semantic(
            kind: IdentifierSemanticKind,
            intent: CallableIntentState,
            resolvedElement: PsiElement? = null,
            source: CallAnalysisSource = CallAnalysisSource.STABLE_PSI
        ): CallAnalysis = CallAnalysis(
            semanticKind = kind,
            structuralKind = null,
            intent = intent,
            resolvedElement = resolvedElement,
            source = source
        )

        fun structural(
            kind: IdentifierStructuralKind,
            intent: CallableIntentState,
            resolvedElement: PsiElement? = null,
            source: CallAnalysisSource = CallAnalysisSource.STABLE_PSI
        ): CallAnalysis = CallAnalysis(
            semanticKind = null,
            structuralKind = kind,
            intent = intent,
            resolvedElement = resolvedElement,
            source = source
        )
    }
}
