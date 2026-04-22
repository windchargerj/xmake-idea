package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.editor.typing.XMakeLuaTypedHandler

class XMakeTypingPairInsertionTest : XMakeTestCase() {

    private var originalPairBracket = false
    private var originalPairQuote = false
    private val handler = XMakeLuaTypedHandler()

    override fun setUp() {
        super.setUp()
        val settings = CodeInsightSettings.getInstance()
        originalPairBracket = settings.AUTOINSERT_PAIR_BRACKET
        originalPairQuote = settings.AUTOINSERT_PAIR_QUOTE
        settings.AUTOINSERT_PAIR_BRACKET = true
        settings.AUTOINSERT_PAIR_QUOTE = true
    }

    override fun tearDown() {
        try {
            val settings = CodeInsightSettings.getInstance()
            settings.AUTOINSERT_PAIR_BRACKET = originalPairBracket
            settings.AUTOINSERT_PAIR_QUOTE = originalPairQuote
        } finally {
            super.tearDown()
        }
    }

    fun testTypingOpenParenInsertsClosingParen() {
        myFixture.configureByText("xmake.lua", "print(<caret>")

        var result = TypedHandlerDelegate.Result.CONTINUE
        WriteCommandAction.runWriteCommandAction(project) {
            result = handler.charTyped('(', project, myFixture.editor, myFixture.file)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        assertEquals(TypedHandlerDelegate.Result.STOP, result)
        assertEquals("print()", myFixture.editor.document.text)
        assertEquals("print(".length, myFixture.editor.caretModel.offset)
    }

    fun testTypingDoubleQuoteInsertsClosingQuote() {
        myFixture.configureByText("xmake.lua", "target(\"<caret>)")

        var result = TypedHandlerDelegate.Result.CONTINUE
        WriteCommandAction.runWriteCommandAction(project) {
            result = handler.charTyped('"', project, myFixture.editor, myFixture.file)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        assertEquals(TypedHandlerDelegate.Result.STOP, result)
        assertEquals("target(\"\")", myFixture.editor.document.text)
        assertEquals("target(\"".length, myFixture.editor.caretModel.offset)
    }

    fun testTypingClosingParenSkipsExistingClosingParen() {
        myFixture.configureByText("xmake.lua", "print(<caret>)")

        assertEquals(
            TypedHandlerDelegate.Result.STOP,
            handler.beforeCharTyped(')', project, myFixture.editor, myFixture.file, myFixture.file.fileType)
        )
        assertEquals("print()".length, myFixture.editor.caretModel.offset)
        assertEquals("print()", myFixture.file.text)
    }

    fun testRealTypingOpenParenInOnBuildAddsSingleClosingParen() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("abc")
                on_build<caret>
            target_end()
            """.trimIndent()
        )

        myFixture.type('(')

        assertEquals(
            """
            target("abc")
                on_build()
            target_end()
            """.trimIndent(),
            myFixture.editor.document.text.trim()
        )
    }

    fun testRealTypingQuoteInAddFilesAddsClosingQuote() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("abc")
                add_files(<caret>)
            target_end()
            """.trimIndent()
        )

        myFixture.type('"')

        assertEquals(
            """
            target("abc")
                add_files("")
            target_end()
            """.trimIndent(),
            myFixture.editor.document.text.trim()
        )
    }

    fun testRealTypingQuoteInsideOrdinaryStringDoesNotInsertPair() {
        myFixture.configureByText(
            "xmake.lua",
            """set_values("message", "hello <caret>world")"""
        )

        myFixture.type('\'')

        assertEquals(
            """set_values("message", "hello 'world")""",
            myFixture.editor.document.text
        )
    }

    fun testEnterAfterForDoIndentsLoopBody() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                for _, name in ipairs({"pthread", "dl"}) do<caret>
            target_end()
            """.trimIndent()
        )

        myFixture.type('\n')

        assertEquals(
            """
            target("demo")
                for _, name in ipairs({"pthread", "dl"}) do
                    ${""}
            target_end()
            """.trimIndent(),
            myFixture.editor.document.text
        )
        assertEquals(
            myFixture.editor.document.text.indexOf("        ") + 8,
            myFixture.editor.caretModel.offset
        )
    }

    fun testEnterAfterFunctionHeaderIndentsFunctionBody() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function(target)<caret>
                end)
            target_end()
            """.trimIndent()
        )

        myFixture.type('\n')

        assertEquals(
            """
            target("demo")
                on_load(function(target)
                    ${""}
                end)
            target_end()
            """.trimIndent(),
            myFixture.editor.document.text
        )
        assertEquals(
            myFixture.editor.document.text.indexOf("        ") + 8,
            myFixture.editor.caretModel.offset
        )
    }

    fun testEnterAfterTargetUsesDomainIndent() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")<caret>
            target_end()
            """.trimIndent()
        )

        myFixture.type('\n')

        assertEquals(
            """
            target("demo")
                ${""}
            target_end()
            """.trimIndent(),
            myFixture.editor.document.text
        )
        assertEquals(
            myFixture.editor.document.text.indexOf("    ") + 4,
            myFixture.editor.caretModel.offset
        )
    }
}
