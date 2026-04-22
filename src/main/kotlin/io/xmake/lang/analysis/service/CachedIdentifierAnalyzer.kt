package io.xmake.lang.analysis.service

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import io.xmake.lang.analysis.lua.LuaCallableAnalyzer
import io.xmake.lang.analysis.lua.LuaSymbolAnalyzer
import io.xmake.lang.analysis.model.IdentifierAnalysisResult
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal class CachedIdentifierAnalyzer : CachedAnalyzer<IdentifierAnalysisResult>() {

    override val cacheKey: Key<CachedValue<IdentifierAnalysisResult>> = CACHE_KEY

    private val validator = CachedIdentifierValidator()

    fun analyze(element: XMakeLuaIdentifier): IdentifierAnalysisResult {
        return getOrPut(element) {
            val callableInfo = LuaCallableAnalyzer.classify(element)
            val symbolAnalysis = LuaSymbolAnalyzer.classifyWithCallableInfo(element, callableInfo)

            IdentifierAnalysisResult(
                semanticKind = symbolAnalysis.semanticKind,
                structuralKind = symbolAnalysis.structuralKind,
                resolutionStatus = symbolAnalysis.resolutionStatus,
                callableInfo = callableInfo,
                validationError = validator.validate(element, callableInfo),
                resolvedElement = symbolAnalysis.resolvedElement
            )
        }
    }

    private companion object {
        private val CACHE_KEY = Key.create<CachedValue<IdentifierAnalysisResult>>("xmake.identifier.analysis")
    }
}
