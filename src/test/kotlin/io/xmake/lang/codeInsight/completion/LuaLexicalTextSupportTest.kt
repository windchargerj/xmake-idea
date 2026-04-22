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
    fun keepsCommentDetectionOutOfQuotedDashes() {
        assertFalse(LuaLexicalTextSupport.isInSingleLineComment("""print("-- not comment")"""))
        assertTrue(LuaLexicalTextSupport.isInSingleLineComment("""print("ok") -- comment"""))
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

