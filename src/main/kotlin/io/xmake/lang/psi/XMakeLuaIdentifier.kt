package io.xmake.lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import io.xmake.lang.XMakeLuaLanguage
import org.antlr.intellij.adaptor.psi.ANTLRPsiLeafNode
import org.antlr.intellij.adaptor.psi.Trees

class XMakeLuaIdentifier(type: IElementType?, text: CharSequence?) : ANTLRPsiLeafNode(type, text),
    PsiNamedElement {

    override fun getName(): String = text

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {
        val newID = Trees.createLeafFromText(project, XMakeLuaLanguage, context, name, elementType)
        return newID?.let { replace(it) } ?: this
    }
}
