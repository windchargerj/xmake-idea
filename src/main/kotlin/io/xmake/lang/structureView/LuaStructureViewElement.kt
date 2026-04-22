package io.xmake.lang.structureView

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.descendantsOfType
import io.xmake.lang.psi.XMakeLuaFile
import io.xmake.lang.psi.xmake.DomainScope

open class LuaStructureViewElement(@JvmField protected val element: PsiElement) : StructureViewTreeElement,
    SortableTreeElement {
    override fun getValue(): Any {
        return element
    }

    override fun navigate(requestFocus: Boolean) {
        if (element is NavigationItem) {
            (element as NavigationItem).navigate(requestFocus)
        }
    }

    override fun canNavigate(): Boolean {
        return element is NavigationItem &&
                (element as NavigationItem).canNavigate()
    }

    override fun canNavigateToSource(): Boolean {
        return element is NavigationItem &&
                (element as NavigationItem).canNavigateToSource()
    }

    override fun getAlphaSortKey(): String {
        val s = if (element is PsiNamedElement) element.name else null
        if (s == null) return "unknown key"
        return s
    }

    override fun getPresentation(): ItemPresentation {
        return when(element) {
            is DomainScope-> XMakeDomainItemPresentation(element)
            else -> LuaItemPresentation(element) }
    }

    override fun getChildren(): Array<out TreeElement?> {
        if (element is XMakeLuaFile) {
            val treeElements = element.descendantsOfType<DomainScope>().map {
                LuaStructureViewElement(it)
            }.toList()
            return treeElements.toTypedArray<TreeElement>()
        }
        return arrayOfNulls(0)
    }
}

