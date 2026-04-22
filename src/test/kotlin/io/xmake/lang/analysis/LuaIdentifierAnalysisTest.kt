package io.xmake.lang.analysis

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.lua.LuaCallableAnalyzer
import io.xmake.lang.analysis.lua.LuaSymbolAnalyzer
import io.xmake.lang.analysis.lua.LuaTypeInference
import io.xmake.lang.analysis.model.CallableIntentState
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.IdentifierStructuralKind

/**
 * Tests the repository's identifier analysis model through the real type-inference
 * and analyzer entry points.
 *
 * These assertions cover stable type inference and identifier kinds
 * on the precise-only analysis baseline.
 */
class LuaIdentifierAnalysisTest : XMakeTestCase() {

    fun testDoesNotInferAliasedHookParameterType() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function (target)
                        local t = target
                        local copy = <caret>t
                    end)
                target_end()
            """.trimIndent()
        )

        assertNull(LuaTypeInference.inferType(identifier))
    }

    fun testDoesNotClassifyAliasedUnknownReceiverAsInstanceMethod() {
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

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertNull(analysis.semanticKind)
        assertNull(analysis.highlightKind)
    }

    fun testDoesNotInferHiddenModuleTypeForInheritedImportQualifier() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function (target)
                        import("core.base.json", {inherit = true})
                        js<caret>on.encode({})
                    end)
                target_end()
            """.trimIndent()
        )

        assertNull(LuaTypeInference.inferType(identifier))
    }

    fun testDoesNotInferSuperModuleTypeForInheritedImportQualifier() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function (target)
                        import("core.base.json", {inherit = true})
                        _su<caret>per.encode({})
                    end)
                target_end()
            """.trimIndent()
        )

        assertNull(LuaTypeInference.inferType(identifier))
    }

    fun testClassifiesDescriptionApiCall() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    after_bu<caret>ild(function (target)
                    end)
                target_end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierSemanticKind.DESCRIPTION_API_CALL, analysis.semanticKind)
        assertEquals(IdentifierSemanticKind.DESCRIPTION_API_CALL, analysis.highlightKind)
    }

    fun testClassifiesConfigurationDomainEnd() {
        val identifier = identifierAtCaret(
            """
                option("demo")
                    set_default(false)
                option_<caret>end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_END, analysis.structuralKind)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_END, analysis.highlightKind)
    }

    fun testClassifiesNamespaceEntry() {
        val identifier = identifierAtCaret(
            """
                name<caret>space("demo")
                    target("hello")
                        set_kind("binary")
                namespace_end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierStructuralKind.NAMESPACE_ENTRY, analysis.structuralKind)
        assertEquals(IdentifierStructuralKind.NAMESPACE_ENTRY, analysis.highlightKind)
    }

    fun testClassifiesNamespaceEnd() {
        val identifier = identifierAtCaret(
            """
                namespace("demo")
                    target("hello")
                        set_kind("binary")
                namespace_<caret>end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierStructuralKind.NAMESPACE_END, analysis.structuralKind)
        assertEquals(IdentifierStructuralKind.NAMESPACE_END, analysis.highlightKind)
    }

    fun testClassifiesScriptBuiltinApiCall() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    after_build(function (target)
                        im<caret>port("core.base.json", {alias = "j"})
                    end)
                target_end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierSemanticKind.SCRIPT_BUILTIN_FUNCTION_CALL, analysis.semanticKind)
        assertEquals(IdentifierSemanticKind.SCRIPT_BUILTIN_FUNCTION_CALL, analysis.highlightKind)
    }

    fun testDirectModuleFunctionCallKeepsDirectCallIntent() {
        val identifier = identifierAtCaret(
            """
                local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent()
        )

        val callable = requireNotNull(LuaCallableAnalyzer.classify(identifier))
        assertEquals(CallableIntentState.DIRECT_CALL, callable.intent)
        assertEquals(IdentifierSemanticKind.MODULE_CALL, callable.semanticKind)
    }

    fun testFunctionReferenceDoesNotProduceCallableIntent() {
        val identifier = identifierAtCaret(
            """
                local function greet()
                end
                local callback = gre<caret>et
            """.trimIndent()
        )

        assertNull(LuaCallableAnalyzer.classify(identifier))
    }

    fun testKeepsMemberReferenceAssignmentAsTableField() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function (target)
                        local joiner = path.jo<caret>in
                    end)
                target_end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierSemanticKind.TABLE_FIELD, analysis.semanticKind)
        assertEquals(IdentifierSemanticKind.TABLE_FIELD, analysis.highlightKind)
    }

    fun testTopLevelTargetAfterHookRemainsConfigurationDomainEntry() {
        val identifier = identifierAtCaret(
            """
                target("one")
                    after_build(function (target)
                        print(target:name())
                    end)
                tar<caret>get("two")
                    set_kind("binary")
                target_end()
            """.trimIndent()
        )

        val analysis = LuaSymbolAnalyzer.classify(identifier)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_ENTRY, analysis.structuralKind)
        assertEquals(IdentifierStructuralKind.CONFIGURATION_DOMAIN_ENTRY, analysis.highlightKind)
    }

}
