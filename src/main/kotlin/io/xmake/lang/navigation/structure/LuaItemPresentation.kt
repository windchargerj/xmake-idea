package io.xmake.lang.navigation.structure

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import javax.swing.Icon

class LuaItemPresentation (val element: PsiElement) : ItemPresentation {
    override fun getIcon(unused: Boolean): Icon {
        return AllIcons.Nodes.Function
    }

    override fun getPresentableText(): String {
        return element.node.text
    }

    override fun getLocationString(): String {
        return element.text
    }
}

