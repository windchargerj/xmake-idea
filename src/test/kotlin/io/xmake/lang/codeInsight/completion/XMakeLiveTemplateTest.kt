package io.xmake.lang.codeInsight.completion

import com.intellij.openapi.actionSystem.IdeActions
import io.xmake.lang.XMakeTestCase

class XMakeLiveTemplateTest : XMakeTestCase() {

    fun testFunctionLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "fn<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("function name()"))
        assertTrue(text.endsWith("end"))
        assertEquals("name", myFixture.editor.selectionModel.selectedText)
    }

    fun testLocalFunctionLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "lfn<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("local function name()"))
        assertTrue(text.endsWith("end"))
        assertEquals("name", myFixture.editor.selectionModel.selectedText)
    }

    fun testIfLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "ife<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("if condition then"))
        assertTrue(text.endsWith("end"))
        assertEquals("condition", myFixture.editor.selectionModel.selectedText)
    }

    fun testForLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "fori<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("for i = 1, 10 do"))
        assertTrue(text.endsWith("end"))
        assertEquals("i", myFixture.editor.selectionModel.selectedText)
    }

    fun testWhileLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "wh<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("while condition do"))
        assertTrue(text.endsWith("end"))
        assertEquals("condition", myFixture.editor.selectionModel.selectedText)
    }

    fun testLocalFunctionLiveTemplateKeepsNestedIndentation() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function(target)
                    lfn<caret>
                end)
            target_end()
            """.trimIndent()
        )

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text
        assertTrue(text.contains("\n        local function name()"))
        assertTrue(text.contains("\n        end"))
        assertEquals("name", myFixture.editor.selectionModel.selectedText)
    }

    fun testAnonymousFunctionLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "afn<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("function("))
        assertTrue(text.endsWith("end"))
    }

    fun testRepeatLiveTemplateExpands() {
        myFixture.configureByText("xmake.lua", "rep<caret>")

        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)

        val text = myFixture.editor.document.text.trim()
        assertTrue(text.startsWith("repeat"))
        assertTrue(text.contains("until condition"))
    }
}
