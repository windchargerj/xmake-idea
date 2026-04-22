package io.xmake.lang.syntax.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

internal object LuaPsiVisibleLeaves {

    fun firstWithin(scope: PsiElement): PsiElement? {
        var leaf = scope.containingFile.findElementAt(scope.textRange.startOffset)
        while (leaf != null && !contains(scope, leaf)) {
            leaf = PsiTreeUtil.nextVisibleLeaf(leaf)
        }
        while (leaf != null && contains(scope, leaf) && leaf.text.isBlank()) {
            leaf = PsiTreeUtil.nextVisibleLeaf(leaf)
        }
        return leaf?.takeIf { contains(scope, it) }
    }

    fun nextWithin(current: PsiElement, scope: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.nextVisibleLeaf(current)
        while (leaf != null && !contains(scope, leaf)) {
            if (leaf.textRange.startOffset >= scope.textRange.endOffset) {
                return null
            }
            leaf = PsiTreeUtil.nextVisibleLeaf(leaf)
        }
        return leaf?.takeIf { contains(scope, it) }
    }

    fun previousWithin(current: PsiElement, scope: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.prevVisibleLeaf(current)
        while (leaf != null && !contains(scope, leaf)) {
            if (leaf.textRange.endOffset <= scope.textRange.startOffset) {
                return null
            }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return leaf?.takeIf { contains(scope, it) }
    }

    fun separatorBetween(
        left: PsiElement,
        right: PsiElement,
        scope: PsiElement
    ): String? {
        var leaf = nextWithin(left, scope)
        var separator: String? = null
        while (leaf != null && leaf != right) {
            when (leaf.text) {
                ".", ":" -> {
                    if (separator != null) {
                        return null
                    }
                    separator = leaf.text
                }

                else -> return null
            }
            leaf = nextWithin(leaf, scope)
        }
        return separator?.takeIf { leaf == right }
    }

    fun findWithin(scope: PsiElement, tokenText: String): PsiElement? {
        var leaf = firstWithin(scope)
        while (leaf != null) {
            if (leaf.text == tokenText) {
                return leaf
            }
            leaf = nextWithin(leaf, scope)
        }
        return null
    }

    private fun contains(scope: PsiElement, leaf: PsiElement): Boolean =
        scope == leaf || PsiTreeUtil.isAncestor(scope, leaf, false)
}
