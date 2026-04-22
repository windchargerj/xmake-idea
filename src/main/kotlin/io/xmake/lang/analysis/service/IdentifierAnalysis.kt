package io.xmake.lang.analysis.service

import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.lua.LuaSymbolResolver
import io.xmake.lang.analysis.lua.LuaTypeInference
import io.xmake.lang.analysis.model.IdentifierAnalysisResult
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

/**
 * Stable analysis boundary for consumers outside the analysis package.
 *
 * Consumers should ask this object for semantic conclusions instead of
 * depending on lower-level analyzers, validators, or inference helpers.
 */
internal object IdentifierAnalysis {

    private val analyzer = CachedIdentifierAnalyzer()

    fun analyze(element: XMakeLuaIdentifier): IdentifierAnalysisResult = analyzer.analyze(element)

    fun declarationTarget(element: XMakeLuaIdentifier): PsiElement? =
        LuaSymbolResolver.resolveVisibleElement(element)

    fun visibleDeclarations(place: PsiElement, beforeOffset: Int = place.textOffset): List<XMakeLuaIdentifier> =
        LuaSymbolResolver.visibleDeclarations(place, beforeOffset)

    fun visibleSymbol(
        place: PsiElement,
        name: String,
        beforeOffset: Int = place.textOffset,
        context: ApiLookupView
    ): VisibleSymbol? =
        when (val symbol = VisibleSymbolResolver.find(place, name, beforeOffset, context)) {
            is VisibleSymbol.Local ->
                symbol.copy(inferredType = LuaTypeInference.inferType(symbol.declaration, context))

            else -> symbol
        }

    fun inferType(identifier: XMakeLuaIdentifier, context: ApiLookupView) =
        LuaTypeInference.inferType(identifier, context)
}
