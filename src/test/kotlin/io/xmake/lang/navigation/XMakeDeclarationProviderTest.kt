package io.xmake.lang.navigation

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.xmake.lang.XMakeTestCase

@Suppress("UnstableApiUsage")
class XMakeDeclarationProviderTest : XMakeTestCase() {

    fun testParameterDeclarationExposesOnlyItsOwnSymbol() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (tar<caret>get)
                end)
            target_end()
            """.trimIndent()
        )

        val targets = declarationTargetsAtCaret()
        val parameterDeclaration = identifierAtCaret()

        assertEquals(listOf(parameterDeclaration), targets)
    }

    fun testParameterUseDoesNotExposeDeclarationProviderSymbol() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    tar<caret>get:add("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent()
        )

        assertEmpty(declarationTargetsAtCaret())
    }

    fun testLocalFunctionDeclarationExposesOnlyItsOwnSymbol() {
        myFixture.configureByText(
            "xmake.lua",
            """
            local function gre<caret>et()
            end
            greet()
            """.trimIndent()
        )

        val targets = declarationTargetsAtCaret()
        val functionDeclaration = identifierAtCaret()

        assertEquals(listOf(functionDeclaration), targets)
    }

    fun testBuiltinApiUseDoesNotExposeDeclarationProviderSymbol() {
        myFixture.configureByText(
            "xmake.lua",
            """
            local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent()
        )

        assertEmpty(declarationTargetsAtCaret())
    }

    private fun declarationTargetsAtCaret(): List<PsiElement> {
        val declarations = allDeclarationsAround(myFixture.file, myFixture.caretOffset)
        return extractElements(declarations.map { it.symbol })
    }

    @Suppress("UNCHECKED_CAST")
    private fun allDeclarationsAround(file: PsiFile, offsetInFile: Int): Collection<PsiSymbolDeclaration> {
        val declarationsClass = Class.forName("com.intellij.model.psi.impl.Declarations")
        val method = declarationsClass.getMethod("allDeclarationsAround", PsiFile::class.java, Int::class.javaPrimitiveType)
        return method.invoke(null, file, offsetInFile) as Collection<PsiSymbolDeclaration>
    }

    private fun extractElements(symbols: Collection<Symbol>): List<PsiElement> {
        val symbolService = PsiSymbolService.getInstance()
        return symbols.mapNotNull(symbolService::extractElementFromSymbol)
    }
}
