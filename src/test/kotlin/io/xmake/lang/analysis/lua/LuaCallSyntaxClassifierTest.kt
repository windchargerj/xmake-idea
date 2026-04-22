package io.xmake.lang.analysis.lua

import io.xmake.lang.XMakeTestCase

/**
 * Plugin-local Lua call-shape analysis coverage.
 *
 * The categories in [LuaCallSyntaxKind] are repository-local syntax-shape categories used by
 * higher layers, not xmake specification vocabulary.
 */
class LuaCallSyntaxClassifierTest : XMakeTestCase() {

    fun testClassifiesLocalFunctionCallAsPlainSyntaxShape() {
        val identifier = identifierAtCaret(
            """
            local function greet(name)
                return name
            end
            gre<caret>et("codex")
            """.trimIndent()
        )

        assertEquals(LuaCallSyntaxKind.PlainCall, LuaCallSyntaxClassifier.classify(identifier))
    }

    fun testClassifiesMemberCall() {
        val identifier = identifierAtCaret(
            """
            local joined = foo.ba<caret>r()
            """.trimIndent()
        )

        assertEquals(LuaCallSyntaxKind.MemberCall, LuaCallSyntaxClassifier.classify(identifier))
    }

    fun testQualifiedFunctionDeclarationDoesNotCreateLocalFunctionCall() {
        val identifier = identifierAtCaret(
            """
            local mod = {}
            function mod.foo()
            end
            fo<caret>o()
            """.trimIndent()
        )

        assertEquals(LuaCallSyntaxKind.PlainCall, LuaCallSyntaxClassifier.classify(identifier))
    }

    fun testClassifiesPlainCall() {
        val identifier = identifierAtCaret(
            """
            add_ru<caret>les("mode.debug")
            """.trimIndent()
        )

        assertEquals(LuaCallSyntaxKind.PlainCall, LuaCallSyntaxClassifier.classify(identifier))
    }

}
