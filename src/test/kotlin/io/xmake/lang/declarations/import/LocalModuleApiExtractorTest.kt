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

    fun testDoesNotExportDottedInterfaceFunctionsUsingPsi() {
        val file = configureModule(
            """
            json = {}

            if cond then
                function json.hidden()
                end
            end

            function json.encode()
            end

            function public_api()
            end
            """.trimIndent()
        )
        val exported = LocalModuleApiExtractor.extractTopLevelPublicFunctionNames(file)

        assertEquals(listOf("public_api"), exported)
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

    fun testIgnoresScriptBuiltinFunctionOverrides() {
        val file = configureModule(
            """
            function print()
            end

            function import()
            end

            function greet()
            end
            """.trimIndent()
        )

        val exported = LocalModuleApiExtractor.extractTopLevelPublicFunctionNames(file)

        // `xmake show -l apis` lists `print` and `import` under script_builtin_apis; sandbox:module()
        // also ignores names that existed in the public scope before the module script ran.
        assertEquals(listOf("greet"), exported)
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

    fun testDoesNotExtractDottedInterfaceDeclarationsAsModuleExports() {
        val file = configureModule(
            """
            json = {}

            function json.encode()
            end

            function public_api()
            end
            """.trimIndent()
        )

        val exports = LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(file)

        // Official source: xmake/core/sandbox/sandbox.lua sandbox:module() exports only new
        // top-level public functions from the module public scope, not `iface.member` functions.
        assertEquals(listOf("public_api"), exports.map { it.name })
        assertNotNull(exports.single().declarationOffset)
        assertEquals("public_api", exports.single().declarationText)
    }

    fun testExtractsTopLevelFunctionValuedAssignmentsAsExports() {
        val file = configureModule(
            """
            assigned = function()
            end

            function declared()
            end
            """.trimIndent()
        )

        val exports = LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(file)

        assertEquals(listOf("assigned", "declared"), exports.map { it.name })
    }

    fun testMarksDynamicTopLevelPublicAssignmentsAsUnknownExports() {
        val file = configureModule(
            """
            assigned = make_api()

            function declared()
            end
            """.trimIndent()
        )

        assertTrue(LocalModuleApiExtractor.hasUnknownTopLevelPublicFunctionExports(file))
    }

    private fun configureModule(text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("module.lua", XMakeLuaFileType, text)
}
