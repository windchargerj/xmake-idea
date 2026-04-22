package io.xmake.lang.resolution

import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.analysis.xmake.XMakeIdentifierResolver
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.resolution.ApiResolutionResult
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object XMakeDeclarationTargets {

    fun resolve(identifier: XMakeLuaIdentifier): PsiElement? {
        if (identifier.isSelfDeclarationTarget()) {
            return identifier
        }

        IdentifierAnalysis.declarationTarget(identifier)?.let { return it }

        val api = XMakeApi.getInstance(identifier.project)
        val context = ApiLookupContext.forIdentifier(identifier)
        return when (val result = XMakeIdentifierResolver.resolveIdentifier(api, identifier, context)) {
            is ApiResolutionResult.Resolved ->
                result.resolution.virtualElement
                    ?: XMakeApiDeclarationService.getInstance(identifier.project).declarationTarget(result.resolution.api)

            is ApiResolutionResult.NotFound -> null
        }
    }

    fun isSyntheticApiDeclaration(element: PsiElement?): Boolean =
        element is XMakeLuaIdentifier &&
            XMakeApiDeclarationService.getInstance(element.project).isSyntheticApiDeclaration(element)
}

private fun XMakeLuaIdentifier.isSelfDeclarationTarget(): Boolean =
    PsiPredicates.isLabel(this) ||
        PsiPredicates.isDeclaration(this) ||
        PsiPredicates.isFunctionDeclarationName(this)
