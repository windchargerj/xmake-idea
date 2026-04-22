package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup

/**
 * Editor insertion behavior for xmake.lua completion.
 *
 * These cases verify how selected items are inserted into the buffer without
 * re-testing parser isolation or insertion behavior.
 */
class XMakeCompletionEditorInsertionTest : XMakeCompletionTestCase() {

    fun testFunctionCompletionInsertionAddsCallParens() {
        myFixture.configureByText("xmake.lua", "set_ki<caret>")
        myFixture.complete(CompletionType.BASIC)
        assertEquals(
            "set_kind()",
            myFixture.file.text.trim()
        )
    }

    fun testDomainEndCompletionInsertionAddsCallParens() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                target_<caret>
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("target_end")
        assertEquals(
            """
            target("demo")
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testStructuralEntryCompletionInsertionCreatesFormattedTargetBlock() {
        myFixture.configureByText("xmake.lua", "tar<caret>")
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("target")
        assertEquals(
            """
            target("")
                
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
        assertEquals("target(\"".length, myFixture.editor.caretModel.offset)
    }

    fun testStructuralEntryCompletionInsertionClosesCurrentDomainBeforeSiblingBlock() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                tar<caret>
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("target")
        assertEquals(
            """
            target("demo")
            target_end()

            target("")
                
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
        assertEquals(
            """
            target("demo")
            target_end()

            target("
            """.trimIndent().length,
            myFixture.editor.caretModel.offset
        )
    }

    fun testDoesNotOfferInstanceMethodCompletionForUnknownHookReceiver() {
        complete {
            """
            target("demo")
                on_load(function (target)
                    target:na<caret>
                end)
            """.trimIndent()
        }.notExpect("name")
    }

    fun testTargetHookCompletionInsertionAddsCallParensOnly() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("abc")
                on_bu<caret>
            target_end()
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("on_build")
        assertEquals(
            """
            target("abc")
                on_build()
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
        assertEquals(
            """
            target("abc")
                on_build(
            """.trimIndent().length,
            myFixture.editor.caretModel.offset
        )
    }

    fun testTargetBuildcmdHookCompletionInsertionAddsCallParensOnly() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("abc")
                on_buildcmd_fi<caret>
            target_end()
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("on_buildcmd_file")
        assertEquals(
            """
            target("abc")
                on_buildcmd_file()
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testPackageComponentHookCompletionInsertionAddsCallParensOnly() {
        myFixture.configureByText(
            "xmake.lua",
            """
            package("abc")
                on_com<caret>
            package_end()
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("on_component")
        assertEquals(
            """
            package("abc")
                on_component()
            package_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testRuleBuildFilesHookCompletionInsertionAddsCallParensOnly() {
        myFixture.configureByText(
            "xmake.lua",
            """
            rule("abc")
                on_build_f<caret>
            rule_end()
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("on_build_files")
        assertEquals(
            """
            rule("abc")
                on_build_files()
            rule_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testImportSegmentCompletionInsertionExtendsStringLiteral() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("co<caret>")
                end)
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC)
        selectCompletion("core")
        assertEquals(
            """
            target("demo")
                on_load(function (target)
                    import("core")
                end)
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    private fun selectCompletion(lookupString: String) {
        myFixture.lookup?.let { lookup ->
            lookup.currentItem = lookup.items.single { it.lookupString == lookupString }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
    }
}

