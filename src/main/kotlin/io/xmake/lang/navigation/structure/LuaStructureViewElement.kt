package io.xmake.lang.navigation.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

open class LuaStructureViewElement(
    @JvmField protected val element: PsiElement,
    private val region: XMakeRegion? = null
) : StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = element is NavigationItem && element.canNavigate()

    override fun canNavigateToSource(): Boolean = element is NavigationItem && element.canNavigateToSource()

    override fun getAlphaSortKey(): String =
        listOfNotNull(
            presentation.presentableText,
            presentation.locationString
        ).joinToString(" ").ifBlank { element.text }

    override fun getPresentation(): ItemPresentation =
        when (element) {
            is LuaFunctionCall if isStructuralOpeningCall(element) ->
                XMakeScopeItemPresentation(element)

            else -> LuaItemPresentation(element)
        }

    override fun getChildren(): Array<out TreeElement?> {
        val file = element.containingFile as? XMakeLuaFile ?: return emptyArray()
        val parentRoot = when {
            element is XMakeLuaFile -> XMakeRoot.Global
            region?.root is XMakeRoot.Namespace -> region.root
            else -> return emptyArray()
        }

        return XMakeScopeQuery.regions(file)
            .asSequence()
            .filter { it.isDirectChildOf(parentRoot) }
            .mapNotNull { childRegion ->
                findOpeningCall(file, childRegion.startOffset)?.let { LuaStructureViewElement(it, childRegion) }
            }
            .distinctBy { (it.value as? PsiElement)?.textRange }
            .toList()
            .toTypedArray()
    }

    private fun findOpeningCall(file: XMakeLuaFile, offset: Int): LuaFunctionCall? {
        val leaf = file.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, LuaFunctionCall::class.java, false)
    }

    private fun XMakeRegion.isDirectChildOf(parentRoot: XMakeRoot): Boolean =
        when (domain) {
            is XMakeDomain.Configuration -> root == parentRoot
            is XMakeDomain.Description -> root is XMakeRoot.Namespace && root.parent == parentRoot
            is XMakeDomain.Script -> false
        }

    private fun isStructuralOpeningCall(call: LuaFunctionCall): Boolean {
        val calleeName = call.calleeName ?: return false
        return calleeName == XMakeDescriptionDomainRules.NAMESPACE_ENTRY_KEYWORD ||
            XMakeConfigurationDomainType.fromKeyword(calleeName) != null
    }
}
