package io.xmake.lang.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.psi.lua.LuaString
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.ScriptScope

class XMakeLuaFoldingBuilder : CustomFoldingBuilder() {

    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        element: PsiElement,
        document: Document,
        quick: Boolean
    ) {
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is DomainScope -> {
                        val range = element.textRange
                        if (range.length > 0) {
                            val firstLeafText = PsiTreeUtil.getDeepestFirst(element).text
                            val firstString = PsiTreeUtil.findChildOfType(element, LuaString::class.java)?.text ?: ""

                            val placeholder = "$firstLeafText($firstString)"
                            descriptors.add(FoldingDescriptor(element.node, range, null, placeholder))
                        }
                    }

                    is ScriptScope -> {
                        val range = element.textRange
                        if (range.length > 0) {
                            descriptors.add(FoldingDescriptor(element.node, range))
                        }
                    }
                }
                super.visitElement(element)
            }
        })
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String = "..."

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false
}
