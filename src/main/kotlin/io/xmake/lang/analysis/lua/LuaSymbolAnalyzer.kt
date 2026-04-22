package io.xmake.lang.analysis.lua

import io.xmake.lang.analysis.model.CallAnalysis
import io.xmake.lang.analysis.model.IdentifierResolutionStatus
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.xmake.XMakeIdentifierResolver
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object LuaSymbolAnalyzer {

    fun classify(element: XMakeLuaIdentifier): LuaSymbolAnalysis =
        classifyWithCallableInfo(element, null)

    internal fun classifyWithCallableInfo(
        element: XMakeLuaIdentifier,
        callableInfo: CallAnalysis?
    ): LuaSymbolAnalysis {
        val api = XMakeApi.getInstance(element.project)
        val apiContext = ApiLookupContext.forIdentifier(element)
        val callable = callableInfo ?: LuaCallableAnalyzer.classify(element)

        return when {
            PsiPredicates.isFunctionDefinition(element) || PsiPredicates.isFunctionDeclarationName(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.FUNCTION_DECLARATION, element)

            PsiPredicates.isLabel(element) || PsiPredicates.isGotoLabelReference(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.LABEL, element)

            PsiPredicates.isLocalVariable(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.LOCAL_VARIABLE, element)

            PsiPredicates.isParameter(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.PARAMETER, element)

            element.isNestedCallQualifier ->
                if (XMakeIdentifierResolver.isUnresolvedNestedQualifier(api, element, apiContext)) {
                    LuaSymbolAnalysis.unresolved(IdentifierResolutionStatus.UNRESOLVED_VARIABLE)
                } else {
                    LuaSymbolAnalysis.neutral()
                }

            callable?.isStableFact == true ->
                LuaSymbolAnalysis(
                    semanticKind = callable.semanticKind,
                    structuralKind = callable.structuralKind,
                    resolutionStatus = IdentifierResolutionStatus.RESOLVED,
                    resolvedElement = callable.resolvedElement
                )

            callable != null ->
                LuaSymbolAnalysis.neutral(callable.resolvedElement)

            PsiPredicates.isTableKey(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.TABLE_KEY, element)

            PsiPredicates.isTableFieldAccess(element) ->
                LuaSymbolAnalysis.semantic(IdentifierSemanticKind.TABLE_FIELD, element)

            else -> classifyNonCallableSymbol(element, api, apiContext)
        }
    }

    private fun classifyNonCallableSymbol(
        element: XMakeLuaIdentifier,
        api: XMakeApi,
        apiContext: ApiLookupView
    ): LuaSymbolAnalysis {
        val localResolution = LuaSymbolResolver.resolveLocal(element) as? XMakeLuaIdentifier
        val syntheticResolution = LuaSymbolResolver.resolveSynthetic(element)
        val inferredType = LuaTypeInference.inferType(element, apiContext)

        return when {
            localResolution != null ->
                classifyResolvedLocal(localResolution)

            syntheticResolution != null ->
                LuaSymbolAnalysis.neutral(syntheticResolution.declarationElement)

            inferredType is XMakeType.Module || inferredType is XMakeType.Instance ->
                LuaSymbolAnalysis.neutral()

            PsiPredicates.isUnresolvedVariableCandidate(element) ->
                LuaSymbolAnalysis.unresolved(IdentifierResolutionStatus.UNRESOLVED_VARIABLE)

            XMakeIdentifierResolver.isUnresolvedApiCall(api, element, apiContext) ->
                LuaSymbolAnalysis.unresolved(IdentifierResolutionStatus.UNRESOLVED_FUNCTION)

            else -> LuaSymbolAnalysis.neutral()
        }
    }

    private fun classifyResolvedLocal(resolved: XMakeLuaIdentifier): LuaSymbolAnalysis {
        return when {
            PsiPredicates.isLabel(resolved) -> LuaSymbolAnalysis.semantic(IdentifierSemanticKind.LABEL, resolved)
            PsiPredicates.isParameter(resolved) -> LuaSymbolAnalysis.semantic(IdentifierSemanticKind.PARAMETER, resolved)
            PsiPredicates.isLocalVariable(resolved) -> LuaSymbolAnalysis.semantic(IdentifierSemanticKind.LOCAL_VARIABLE, resolved)
            else -> LuaSymbolAnalysis.neutral(resolved)
        }
    }
}
