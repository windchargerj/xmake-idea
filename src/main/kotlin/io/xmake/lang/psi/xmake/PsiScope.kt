package io.xmake.lang.psi.xmake

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.xmake.lang.api.model.BuildScope
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode
import org.antlr.intellij.adaptor.psi.ScopeNode

abstract class PsiScope(node: ASTNode) : ANTLRPsiNode(node), ScopeNode {
    abstract val xmakeScope: BuildScope

    override fun resolve(element: PsiNamedElement): PsiElement? = null
}
