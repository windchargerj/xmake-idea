package io.xmake.lang.navigation.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

open class LuaStructureViewElement(
    @JvmField protected val element: PsiElement
) : StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigationItem)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = element is NavigationItem && element.canNavigate()

    override fun canNavigateToSource(): Boolean = element is NavigationItem && element.canNavigateToSource()

    override fun getAlphaSortKey(): String = (element as? PsiNamedElement)?.name ?: "unknown key"

    override fun getPresentation(): ItemPresentation =
        when (element) {
            is LuaFunctionCall if isDomainOpeningCall(element) ->
                XMakeScopeItemPresentation(element)

            else -> LuaItemPresentation(element)
        }

    override fun getChildren(): Array<out TreeElement?> {
        if (element !is XMakeLuaFile) {
            return emptyArray()
        }

        return XMakeScopeQuery.regions(element)
            .asSequence()
            .filter { it.domain is XMakeDomain.Configuration }
            .mapNotNull { region -> findOpeningCall(element, region.startOffset) }
            .distinctBy { it.textRange }
            .map(::LuaStructureViewElement)
            .toList()
            .toTypedArray()
    }

    private fun findOpeningCall(file: XMakeLuaFile, offset: Int): LuaFunctionCall? {
        val leaf = file.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, LuaFunctionCall::class.java, false)
    }

    private fun isDomainOpeningCall(call: LuaFunctionCall): Boolean =
        call.firstChild?.text?.let(XMakeConfigurationDomainType::fromKeyword) != null &&
            PsiTreeUtil.getParentOfType(call, LuaFunctionCall::class.java, true, XMakeLuaFile::class.java) == null
}
