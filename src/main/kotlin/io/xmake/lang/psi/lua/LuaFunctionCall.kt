package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

class LuaFunctionCall(node: ASTNode) : ANTLRPsiNode(node) {

    fun getArguments(): List<PsiElement> {
        val argsNode = PsiTreeUtil.findChildOfType(this, LuaArgs::class.java)
        return argsNode?.getArguments() ?: emptyList()
    }
}