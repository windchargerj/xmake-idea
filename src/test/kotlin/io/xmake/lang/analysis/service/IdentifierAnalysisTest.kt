package io.xmake.lang.analysis.service

import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.model.IdentifierResolutionStatus
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.IdentifierStructuralKind
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.utils.info.XMakeApis
import io.xmake.utils.info.XMakeInfoManager

class IdentifierAnalysisTest : XMakeTestCase() {

    fun testUnresolvedKindsDoNotProduceHighlightKinds() {
        val identifier = identifierAtCaret(
            """
            missing_<caret>api()
            """.trimIndent()
        )

        val analysis = IdentifierAnalysis.analyze(identifier)

        assertEquals(IdentifierResolutionStatus.UNRESOLVED_FUNCTION, analysis.resolutionStatus)
        assertNull(analysis.highlightKind)
    }

    fun testValidationErrorComesFromUnifiedIdentifierAnalysis() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    set_<caret>project("demo")
                end)
            target_end()
            """.trimIndent()
        )

        val error = requireNotNull(IdentifierAnalysis.analyze(identifier).validationError)
        assertTrue(error.message.contains("script domain"))
        assertTrue(error.message.contains("global"))
    }

    fun testReferenceCandidateFiltersParametersButKeepsModuleFunctions() {
        val parameter = identifierAtCaret(
            """
            local function greet(na<caret>me)
                return name
            end
            """.trimIndent()
        )
        assertFalse(IdentifierAnalysis.analyze(parameter).isReferenceCandidate)

        val moduleFunction = identifierAtCaret(
            """
            local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent()
        )
        assertTrue(IdentifierAnalysis.analyze(moduleFunction).isReferenceCandidate)
    }

    fun testAnalysisResultCarriesResolvedLocalDeclaration() {
        val identifier = identifierAtCaret(
            """
            local value = 1
            local copy = va<caret>lue
            """.trimIndent()
        )

        val resolvedElement = IdentifierAnalysis.analyze(identifier).resolvedElement as? XMakeLuaIdentifier
        assertNotNull(resolvedElement)
        assertEquals("value", resolvedElement?.name)
        assertTrue(resolvedElement !== identifier)
    }

    fun testAnalysisInvalidatesAfterApiReloadWithoutPsiChange() {
        val identifier = identifierAtCaret(
            """
            set_<caret>project("demo")
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.DESCRIPTION_API_CALL, IdentifierAnalysis.analyze(identifier).highlightKind)

        XMakeInfoManager.getInstance(project).xmakeInfo.apis = XMakeApis()
        ApiService.getInstance(project).reload()

        val reloaded = IdentifierAnalysis.analyze(identifier)
        assertEquals(IdentifierResolutionStatus.UNRESOLVED_FUNCTION, reloaded.resolutionStatus)
        assertNull(reloaded.highlightKind)
    }

    fun testAnalysisSeparatesSemanticAndStructuralKinds() {
        val apiIdentifier = identifierAtCaret(
            """
            target("demo")
                after_bu<caret>ild(function (target)
                end)
            target_end()
            """.trimIndent()
        )
        val apiAnalysis = IdentifierAnalysis.analyze(apiIdentifier)
        assertEquals(IdentifierSemanticKind.DESCRIPTION_API_CALL, apiAnalysis.semanticKind)
        assertNull(apiAnalysis.structuralKind)
        assertEquals(IdentifierSemanticKind.DESCRIPTION_API_CALL, apiAnalysis.highlightKind)

        val structureIdentifier = identifierAtCaret(
            """
            option("demo")
                set_default(false)
            option_<caret>end()
            """.trimIndent()
        )
        val structureAnalysis = IdentifierAnalysis.analyze(structureIdentifier)
        assertNull(structureAnalysis.semanticKind)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_END, structureAnalysis.structuralKind)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_END, structureAnalysis.highlightKind)
    }

    fun testVisibleSymbolReturnsBuiltinApi() {
        val identifier = identifierAtCaret(
            """
            set_<caret>project("demo")
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(io.xmake.lang.scope.query.XMakeScopeQuery.stateAt(identifier))
        val symbol = IdentifierAnalysis.visibleSymbol(identifier, identifier.text, identifier.textOffset, context)

        val builtin = symbol as? VisibleSymbol.BuiltinApi
        assertNotNull(builtin)
        assertEquals("set_project", builtin?.api?.name)
    }

    fun testVisibleSymbolReturnsInheritedApi() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    enco<caret>de({})
                end)
            target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(io.xmake.lang.scope.query.XMakeScopeQuery.stateAt(identifier))
        val symbol = IdentifierAnalysis.visibleSymbol(identifier, identifier.text, identifier.textOffset, context)

        val inherited = symbol as? VisibleSymbol.InheritedApi
        assertNotNull(inherited)
        assertEquals("encode", inherited?.api?.name)
    }

    fun testVisibleSymbolReturnsImportedModuleBinding() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    js<caret>on.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(io.xmake.lang.scope.query.XMakeScopeQuery.stateAt(identifier))
        val symbol = IdentifierAnalysis.visibleSymbol(identifier, identifier.text, identifier.textOffset, context)

        val imported = symbol as? VisibleSymbol.ImportedModule
        assertNotNull(imported)
        assertEquals("core.base.json", imported?.symbol?.module?.identifier)
    }

}
