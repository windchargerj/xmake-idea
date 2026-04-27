package io.xmake.lang.editor.formatting

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import io.xmake.lang.XMakeTestCase

class XMakeFormattingModelTest : XMakeTestCase() {

    fun testReformatIndentsDomainStatements() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
            set_kind("binary")
            add_files("src/*.c")
            target_end()
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            target("demo")
                set_kind("binary")
                add_files("src/*.c")
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testReformatIndentsScriptBodyInsideHook() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                print(target:name())
                end)
            target_end()
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            target("demo")
                on_load(function(target)
                    print(target:name())
                end)
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testReformatKeepsUnaryOperatorsTight() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    local value = a*-b
                    local nested = foo(-x)
                    local flags = {~mask}
                end)
            target_end()
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            target("demo")
                on_load(function(target)
                    local value = a * -b
                    local nested = foo(-x)
                    local flags = {~mask}
                end)
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testReformatIndentsForBodyOnce() {
        myFixture.configureByText(
            "xmake.lua",
            """
            for _, name in ipairs({"pthread", "dl"}) do
            add_links(name)
            end
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            for _, name in ipairs({"pthread", "dl"}) do
                add_links(name)
            end
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testReformatIndentsForBodyOnceInsideTarget() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
            for _, name in ipairs({"pthread", "dl"}) do
            add_links(name)
            end
            target_end()
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            target("demo")
                for _, name in ipairs({"pthread", "dl"}) do
                    add_links(name)
                end
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    fun testReformatIndentsForBodyOnceInsideHook() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                for _, name in ipairs({"pthread", "dl"}) do
                add_links(name)
                end
                end)
            target_end()
            """.trimIndent()
        )

        reformat()

        assertEquals(
            """
            target("demo")
                on_load(function(target)
                    for _, name in ipairs({"pthread", "dl"}) do
                        add_links(name)
                    end
                end)
            target_end()
            """.trimIndent(),
            myFixture.file.text.trim()
        )
    }

    private fun reformat() {
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
    }
}
