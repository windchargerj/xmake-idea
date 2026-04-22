package io.xmake.lang.psi.xmake

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.xmake.lang.api.model.BuildScope

class ScriptScope(node: ASTNode) : PsiScope(node) {
    override val xmakeScope: BuildScope get() = BuildScope.Script

    override fun resolve(element: PsiNamedElement): PsiElement? = null
}
