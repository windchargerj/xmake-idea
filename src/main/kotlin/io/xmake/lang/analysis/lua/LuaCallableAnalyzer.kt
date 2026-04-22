package io.xmake.lang.analysis.lua

import io.xmake.lang.analysis.model.CallableIntentState
import io.xmake.lang.analysis.model.CallAnalysis
import io.xmake.lang.analysis.model.CallAnalysisSource
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.analysis.xmake.XMakeCallableAnalyzer
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object LuaCallableAnalyzer {

    fun classify(element: XMakeLuaIdentifier): CallAnalysis? {
        when (val callKind = LuaCallSyntaxClassifier.classify(element)) {
            LuaCallSyntaxKind.PlainCall ->
                resolveLocalFunctionDeclaration(element)?.let { declaration ->
                    return CallAnalysis.semantic(
                        kind = IdentifierSemanticKind.FUNCTION_CALL,
                        intent = CallableIntentState.DIRECT_CALL,
                        resolvedElement = declaration
                    )
                } ?: XMakeCallableAnalyzer.analyzeDirectCall(element, callKind)?.let { return it }

            LuaCallSyntaxKind.MemberCall ->
                XMakeCallableAnalyzer.analyzeDirectCall(element, callKind)?.let { return it }

            LuaCallSyntaxKind.None -> Unit
        }

        val localResolution = resolveLocalFunctionDeclaration(element)
        if (localResolution != null && element.isEditorRecoveryCallHead) {
            return CallAnalysis.semantic(
                kind = IdentifierSemanticKind.FUNCTION_CALL,
                intent = CallableIntentState.EDITOR_RECOVERY_CALL_HEAD,
                resolvedElement = localResolution,
                source = CallAnalysisSource.EDITOR_RECOVERY
            )
        }

        return XMakeCallableAnalyzer.analyzeEditorRecoveryCallHead(element)
    }

    private fun resolveLocalFunctionDeclaration(element: XMakeLuaIdentifier): XMakeLuaIdentifier? {
        val localResolution = LuaSymbolResolver.resolveLocal(element) as? XMakeLuaIdentifier
        return localResolution?.takeIf(PsiPredicates::isBindableFunctionDeclarationName)
    }
}
