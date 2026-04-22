package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

class LuaExpressionList(node: ASTNode) : ANTLRPsiNode(node) {

    fun getExpressions(): List<PsiElement> {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, LuaExpression::class.java)
    }
}