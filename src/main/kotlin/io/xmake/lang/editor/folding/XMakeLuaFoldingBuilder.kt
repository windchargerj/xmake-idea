package io.xmake.lang.editor.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaString

class XMakeLuaFoldingBuilder : CustomFoldingBuilder(), DumbAware {

    companion object {
        private const val SCRIPT_SCOPE_FOLD_THRESHOLD = 5000
    }

    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        element: PsiElement,
        document: Document,
        quick: Boolean
    ) {
        val file = element.containingFile ?: return
        XMakeScopeQuery.regions(file).forEach { region ->
            when (region.domain) {
                is XMakeDomain.Configuration -> addConfigurationDescriptor(descriptors, file, region)
                is XMakeDomain.Script -> addScriptDescriptor(descriptors, file, region)
                is XMakeDomain.Description -> Unit
            }
        }
    }

    private fun addConfigurationDescriptor(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        region: XMakeRegion
    ) {
        if (region.range.length <= 0) {
            return
        }
        val call = findOpeningCall(root, region) ?: return
        val anchor = findAnchor(root, region.range) ?: return
        val scope = region.domain as? XMakeDomain.Configuration ?: return
        val firstString = PsiTreeUtil.findChildOfType(call, LuaString::class.java)?.text ?: ""
        val placeholder = "${scope.type.toKeyword()}($firstString)"
        descriptors.add(FoldingDescriptor(anchor.node, region.range, null, placeholder))
    }

    private fun addScriptDescriptor(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        region: XMakeRegion
    ) {
        if (region.range.length <= SCRIPT_SCOPE_FOLD_THRESHOLD) {
            return
        }
        val anchor = findAnchor(root, region.range) ?: return
        descriptors.add(FoldingDescriptor(anchor.node, region.range))
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String = "..."

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false

    private fun findOpeningCall(root: PsiElement, region: XMakeRegion): LuaFunctionCall? {
        val startElement = root.containingFile.findElementAt(region.startOffset) ?: return null
        return PsiTreeUtil.getParentOfType(startElement, LuaFunctionCall::class.java, false)
    }

    private fun findAnchor(root: PsiElement, range: TextRange): PsiElement? {
        val file = root.containingFile
        val start = file.findElementAt(range.startOffset) ?: return file
        val endOffset = (range.endOffset - 1).coerceAtLeast(range.startOffset)
        val end = file.findElementAt(endOffset) ?: return start
        return PsiTreeUtil.findCommonParent(start, end) ?: file
    }
}

