package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

class LuaArgs(node: ASTNode) : ANTLRPsiNode(node) {

    fun getArguments(): List<PsiElement> {
        // (explist?)
        val explist = PsiTreeUtil.findChildOfType(this, LuaExpressionList::class.java)
        if (explist != null) {
            return explist.getExpressions()
        }

        // tableconstructor
        val tableConstructor = PsiTreeUtil.findChildOfType(this, LuaTableConstructor::class.java)
        if (tableConstructor != null) {
            return listOf(tableConstructor)
        }

        // string
        val stringLiteral = PsiTreeUtil.findChildOfType(this, LuaString::class.java)
        if (stringLiteral != null) {
            return listOf(stringLiteral)
        }

        return emptyList()
    }
}