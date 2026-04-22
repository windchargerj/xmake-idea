package io.xmake.lang.syntax.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

class LuaArgs(node: ASTNode) : ANTLRPsiNode(node) {

    val arguments: List<PsiElement>
        get() = PsiTreeUtil.findChildOfType(this, LuaExpressionList::class.java)?.getExpressions()
            ?: PsiTreeUtil.findChildOfType(this, LuaTableConstructor::class.java)?.let(::listOf)
            ?: PsiTreeUtil.findChildOfType(this, LuaString::class.java)?.let(::listOf)
            ?: emptyList()
}
