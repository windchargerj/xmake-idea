package io.xmake.lang.codeInsight.completion

import io.xmake.lang.syntax.text.LuaLexicalTextSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaLexicalTextSupportTest {
    private val importFunctions = setOf("import", "inherit")

    @Test
    fun detectsImportPrefixForInheritCalls() {
        val text = """
            target("demo")
                on_load(function ()
                    inherit("core.ba")
                end)
        """.trimIndent()
        val caretOffset = text.indexOf("core.ba") + "core.ba".length

        assertEquals(
            "core.ba",
            LuaLexicalTextSupport.detectCallStringPrefix(text, caretOffset, importFunctions)
        )
    }

    @Test
    fun detectsMemberAccessReceiverAndSeparator() {
        val text = """
            target("demo")
                on_load(function ()
                    json.encode
                end)
        """.trimIndent()
        val caretOffset = text.indexOf("json.encode") + "json.".length

        val access = LuaLexicalTextSupport.detectMemberAccess(text, caretOffset)
        assertNotNull(access)
        assertEquals("json", access?.receiverPath)
        assertEquals('.', access?.separator)
    }

    @Test
    fun detectsUnsupportedMemberAccessSyntaxWithoutPromotingReceiver() {
        val text = """
            target("demo")
                on_load(function ()
                    get_path().join
                end)
        """.trimIndent()
        val caretOffset = text.indexOf("get_path().join") + "get_path().".length

        val access = LuaLexicalTextSupport.detectMemberAccess(text, caretOffset)
        val syntax = LuaLexicalTextSupport.detectMemberAccessSyntax(text, caretOffset)

        assertNull(access)
        assertNotNull(syntax)
        assertEquals('.', syntax?.separator)
    }

    @Test
    fun doesNotTreatLabelsOrDotRunsAsMemberAccessSyntax() {
        assertNull(LuaLexicalTextSupport.detectMemberAccessSyntax("::done", "::done".length))
        assertNull(LuaLexicalTextSupport.detectMemberAccessSyntax("a .. b", "a ..".length))
        assertNull(LuaLexicalTextSupport.detectMemberAccessSyntax("...", "...".length))
    }

    @Test
    fun keepsCommentDetectionOutOfQuotedDashes() {
        assertFalse(LuaLexicalTextSupport.isInSingleLineComment("""print("-- not comment")"""))
        assertTrue(LuaLexicalTextSupport.isInSingleLineComment("""print("ok") -- comment"""))
    }

    @Test
    fun detectsLongBlockCommentOnlyFromCommentOpener() {
        val commentText = """
            --[=[
            block comment
            ]=]
        """.trimIndent()
        val commentOffset = commentText.indexOf("block comment") + "block".length

        val stringText = """
            local value = [=[
            -- not a block comment
            ]=]
        """.trimIndent()
        val stringOffset = stringText.indexOf("not a block comment") + "not".length

        assertTrue(LuaLexicalTextSupport.isInsideBlockComment(commentText, commentOffset))
        assertFalse(LuaLexicalTextSupport.isInsideBlockComment(stringText, stringOffset))
    }

    @Test
    fun closesLongBlockCommentByMatchingEqualsLevel() {
        val text = """
            --[=[
            still comment ]==]
            ]=]
            after
        """.trimIndent()

        val stillCommentOffset = text.indexOf("still comment") + "still".length
        val afterOffset = text.indexOf("after") + "after".length

        assertTrue(LuaLexicalTextSupport.isInsideBlockComment(text, stillCommentOffset))
        assertFalse(LuaLexicalTextSupport.isInsideBlockComment(text, afterOffset))
    }

    @Test
    fun extractsImportPrefixWithoutCompletionPlaceholder() {
        assertEquals(
            "core.base",
            LuaLexicalTextSupport.extractQuotedPrefix("\"core.baseIntellijIdeaRulezzz\"", "IntellijIdeaRulezzz")
        )
        assertNull(
            LuaLexicalTextSupport.extractQuotedPrefix("\"IntellijIdeaRulezzz\"", "IntellijIdeaRulezzz")
        )
    }
}

