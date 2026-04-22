package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.xmake.lang.XMakeLuaLanguage
import org.antlr.intellij.adaptor.SymtabUtils
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode
import org.antlr.intellij.adaptor.psi.ScopeNode

class LuaBlock(node: ASTNode) : ANTLRPsiNode(node), ScopeNode {
    override fun resolve(element: PsiNamedElement): PsiElement? {
        return SymtabUtils.resolve(
            this, XMakeLuaLanguage,
            element, "/block"
        )
    }
}