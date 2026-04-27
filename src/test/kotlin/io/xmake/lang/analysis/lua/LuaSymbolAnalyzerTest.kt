package io.xmake.lang.analysis.lua

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.model.CallAnalysisSource
import io.xmake.lang.analysis.model.IdentifierResolutionStatus
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.service.IdentifierAnalysis

/**
 * Plugin-local Lua symbol taxonomy coverage.
 *
 * These tests guard the repository's own identifier-kind model,
 * independent of xmake's public documentation.
 */
class LuaSymbolAnalyzerTest : XMakeTestCase() {

    fun testClassifiesResolvedLocalVariableUsage() {
        val identifier = identifierAtCaret(
            """
            local value = 1
            local copy = val<caret>ue
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.LOCAL_VARIABLE, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesResolvedParameterUsage() {
        val identifier = identifierAtCaret(
            """
            local function greet(name)
                local copy = na<caret>me
            end
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.PARAMETER, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesResolvedGenericForVariableUsage() {
        val identifier = identifierAtCaret(
            """
            for _, name in ipairs({"pthread", "dl"}) do
                local copy = na<caret>me
            end
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.LOCAL_VARIABLE, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesGotoLabelReference() {
        val identifier = identifierAtCaret(
            """
            goto do<caret>ne
            ::done::
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.LABEL, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesQualifiedFunctionDeclarationName() {
        val identifier = identifierAtCaret(
            """
            local mod = {}
            function mod.fo<caret>o()
            end
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.FUNCTION_DECLARATION, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesLocalFunctionCall() {
        val identifier = identifierAtCaret(
            """
            local function greet()
            end
            gre<caret>et()
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.FUNCTION_CALL, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesEditorRecoveryLocalFunctionCallHeadAsNonStableFact() {
        val identifier = identifierAtCaret(
            """
            local function greet()
            end
            gre<caret>et
            """.trimIndent()
        )

        assertNull(LuaSymbolAnalyzer.classify(identifier).semanticKind)
        assertEquals(CallAnalysisSource.EDITOR_RECOVERY, LuaCallableAnalyzer.classify(identifier)?.source)
        assertFalse(IdentifierAnalysis.analyze(identifier).isReferenceCandidate)
    }

    fun testDoesNotClassifyFunctionReferenceAsDeclaration() {
        val identifier = identifierAtCaret(
            """
            local function greet()
            end
            local callback = gre<caret>et
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertNull(analysis.semanticKind)
        assertNull(analysis.structuralKind)
        assertEquals(IdentifierResolutionStatus.RESOLVED, analysis.resolutionStatus)
    }

    fun testClassifiesAliasedModuleFunctionCall() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local p = path
                    p.jo<caret>in("src", "main.c")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.MODULE_CALL, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testKnownModuleMemberCallTypoIsUnresolvedFunction() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local p = path
                    p.joi<caret>n_typo("src", "main.c")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(
            IdentifierResolutionStatus.UNRESOLVED_FUNCTION,
            LuaSymbolAnalyzer.classify(identifier).resolutionStatus
        )
    }

    fun testClassifiesAliasedVerifiedHookReceiverAsInstanceMethodCall() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local t = target
                    t:a<caret>dd("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.INSTANCE_METHOD, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testKeepsFunctionReturnedModuleCallConservative() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local function get_path()
                        return path
                    end
                    local p = get_path()
                    p.jo<caret>in("src", "main.c")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.TABLE_FIELD, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testDoesNotClassifyFunctionReturnedUnknownReceiverAsInstanceMethodCall() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local function get_target()
                        return target
                    end
                    local t = get_target()
                    t:a<caret>dd("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testUnknownReceiverMemberCallTypoIsNotUnresolvedFunction() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local function get_path()
                        return path
                    end
                    local p = get_path()
                    p.joi<caret>n_typo("src", "main.c")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(IdentifierResolutionStatus.RESOLVED, LuaSymbolAnalyzer.classify(identifier).resolutionStatus)
    }

    fun testDoesNotClassifyMemberReferenceAssignmentAsEditorRecoveryCallHead() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    local joiner = path.jo<caret>in
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.TABLE_FIELD, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesTableFieldAccess() {
        val identifier = identifierAtCaret(
            """
            local ctx = { value = 1 }
            local copy = ctx.va<caret>lue
            """.trimIndent()
        )

        assertEquals(IdentifierSemanticKind.TABLE_FIELD, LuaSymbolAnalyzer.classify(identifier).semanticKind)
    }

    fun testClassifiesUnresolvedVariable() {
        val identifier = identifierAtCaret(
            """
            local bad = miss<caret>ing
            """.trimIndent()
        )

        assertEquals(IdentifierResolutionStatus.UNRESOLVED_VARIABLE, LuaSymbolAnalyzer.classify(identifier).resolutionStatus)
    }

}
