package io.xmake.lang.codeInsight.completion

import com.intellij.psi.PsiElement
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Editor-context detector contract for completion dispatch.
 *
 * This suite validates repository-local context extraction, including imported
 * aliases, verified receiver lifting, and lookup-view selection at the caret.
 */
class CompletionContextDetectorTest : XMakeTestCase() {

    fun testTreatsImportedAliasAsValidModule() {
        val (file, position) = configureAtCaret(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {alias = "j"})
                    j.<caret>
                end)
            target_end()
            """.trimIndent()
        )

        assertTrue(
            CompletionMemberAccessAnalyzer.isValidModule(
                "j",
                position,
                file,
                CompletionScopeResolver.inferLookupView(position, myFixture.editor)
            )
        )
    }

    fun testInfersScriptScopeForBlankHookBodyCaret() {
        val (_, position) = configureAtCaret(
            """
            target("demo")
                on_load(function (target)
                    <caret>
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(XMakeDomain.Script, CompletionScopeResolver.inferLookupView(position, myFixture.editor).domain)
    }

    fun testDetectsInstanceMethodContextFromVerifiedHookParameter() {
        val (file, position) = configureAtCaret(
            """
            target("demo")
                on_load(function (target)
                    target:na<caret>
                end)
            target_end()
            """.trimIndent()
        )

        val result = CompletionMemberAccessAnalyzer.detectContext(
            position,
            myFixture.editor,
            file,
            CompletionScopeResolver.inferLookupView(position, myFixture.editor)
        )

        assertNotNull(result)
        assertEquals("target", result?.receiverPath)
        assertEquals(CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD, result?.memberAccessKind)
    }

    fun testDoesNotTreatBareXMakeNameAsResolvedInstanceMethodWithoutLuaBinding() {
        val (file, position) = configureAtCaret(
            """
            target("demo")
                on_load(function ()
                    target:na<caret>
                end)
            target_end()
            """.trimIndent()
        )

        val result = CompletionMemberAccessAnalyzer.detectContext(
            position,
            myFixture.editor,
            file,
            CompletionScopeResolver.inferLookupView(position, myFixture.editor)
        )

        assertNull(result)
    }

    private fun configureAtCaret(code: String): Pair<XMakeLuaFile, PsiElement> {
        myFixture.configureByText("xmake.lua", code)
        val file = myFixture.file as XMakeLuaFile
        val offset = myFixture.caretOffset
        val position = file.findElementAt(offset)
            ?: file.findElementAt((offset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
        return file to position
    }
}

