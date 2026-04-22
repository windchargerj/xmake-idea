package io.xmake.lang.codeInsight.reference

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.resolution.reference.XMakeReferenceProvider
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeReferenceRenameTest : XMakeTestCase() {

    private val provider = XMakeReferenceProvider()

    fun testLocalVariableReferenceCanBeRenamed() {
        val reference = configureAndFindReference(
            """
            local value = 1
            local copy = val<caret>ue
            """.trimIndent()
        )

        WriteCommandAction.runWriteCommandAction(project) {
            reference.handleElementRename("renamedValue")
        }

        assertContainsElements(
            PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java).mapNotNull { it.name },
            listOf("renamedValue")
        )
        assertFalse(myFixture.file.text.contains("local copy = value"))
    }

    private fun configureAndFindReference(code: String): PsiReference {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val leaf = myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
        val identifier = PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
            ?: leaf as? XMakeLuaIdentifier
            ?: error("No identifier at caret")
        return provider.getReferencesByElement(identifier, ProcessingContext()).single()
    }
}
