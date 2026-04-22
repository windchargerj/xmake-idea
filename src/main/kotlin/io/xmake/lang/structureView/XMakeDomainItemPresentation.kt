package io.xmake.lang.structureView

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.util.descendantsOfType
import io.xmake.icons.XMakeIcons
import io.xmake.lang.psi.lua.LuaArgs
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.DomainScope.DomainType.Companion.toPresentableText
import javax.swing.Icon

class XMakeDomainItemPresentation(private val element: PsiElement): ItemPresentation {
    override fun getPresentableText(): String {
        return (element as DomainScope).type.toPresentableText()
    }

    override fun getLocationString(): String {
        return element.descendantsOfType<LuaArgs>().first().getArguments()
            .firstOrNull()?.text?.removeSurrounding("\"") ?: ""
    }

    override fun getIcon(unused: Boolean): Icon {
        return XMakeIcons.TARGET
    }

}
