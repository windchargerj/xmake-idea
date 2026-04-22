package io.xmake.lang.declarations.import

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.file.XMakeLuaFileType

class LocalModuleApiExtractorTest : XMakeTestCase() {

    fun testExtractsTopLevelFunctionsAfterIfElseIfChainUsingPsi() {
        val file = configureModule(
            """
            if cond then
                function hidden_debug()
                end
            elseif other_cond then
                function hidden_release()
                end
            else
                function hidden_fallback()
                end
            end

            function public_api()
            end
            """.trimIndent()
        )
        val exported = LocalModuleApiExtractor.extractTopLevelPublicFunctionNames(file)

        assertEquals(listOf("public_api"), exported)
    }

    fun testExtractsInterfaceFunctionsAfterIfBlockUsingPsi() {
        val file = configureModule(
            """
            if cond then
                function json.hidden()
                end
            end

            function json.encode()
            end
            """.trimIndent()
        )
        val exported = LocalModuleApiExtractor.extractPublicInterfaceFunctionNames(file, "json")

        assertEquals(listOf("encode"), exported)
    }

    fun testKeepsLoopAndRepeatBlocksBalancedUsingPsi() {
        val file = configureModule(
            """
            for _, value in ipairs(values) do
                function hidden_for()
                end
            end
            function after_for()
            end

            while cond do
                function hidden_while()
                end
            end
            function after_while()
            end

            repeat
                function hidden_repeat()
                end
            until done
            function after_repeat()
            end

            do
                function hidden_do()
                end
            end
            function after_do()
            end
            """.trimIndent()
        )
        val exported = LocalModuleApiExtractor.extractTopLevelPublicFunctionNames(file)

        assertEquals(listOf("after_for", "after_while", "after_repeat", "after_do"), exported)
    }

    fun testIgnoresFunctionLikeTextInsideCommentsAndStrings() {
        val exported = LocalModuleApiExtractor.extractTopLevelPublicFunctionNames(
            project,
            """
            -- function fake_comment()
            local text = "function fake_string() end"
            function real_api()
            end
            """.trimIndent()
        )

        assertEquals(listOf("real_api"), exported)
    }

    fun testExtractsDeclarationBackedTopLevelExportsFromPsi() {
        val file = configureModule(
            """
            function public_api()
            end
            """.trimIndent()
        )

        val exports = LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(file)

        assertEquals(listOf("public_api"), exports.map { it.name })
        assertNotNull(exports.single().declarationOffset)
        assertEquals("public_api", exports.single().declarationText)
    }

    fun testExtractsDeclarationBackedInterfaceExportsFromPsi() {
        val file = configureModule(
            """
            function json.encode()
            end
            """.trimIndent()
        )

        val exports = LocalModuleApiExtractor.extractPublicInterfaceFunctionDeclarations(file, "json")

        assertEquals(listOf("encode"), exports.map { it.name })
        assertNotNull(exports.single().declarationOffset)
        assertEquals("encode", exports.single().declarationText)
    }

    private fun configureModule(text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("module.lua", XMakeLuaFileType, text)
}
