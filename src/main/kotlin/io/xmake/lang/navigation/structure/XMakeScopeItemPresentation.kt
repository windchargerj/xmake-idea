package io.xmake.lang.navigation.structure

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import io.xmake.icons.XMakeIcons
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import javax.swing.Icon

class XMakeScopeItemPresentation(private val element: PsiElement): ItemPresentation {
    override fun getPresentableText(): String {
        val calleeName = (element as? LuaFunctionCall)?.calleeName
        if (calleeName == XMakeDescriptionDomainRules.NAMESPACE_ENTRY_KEYWORD) {
            return "Namespace"
        }

        return calleeName
            ?.let(XMakeConfigurationDomainType::fromKeyword)
            ?.toDisplayName()
            ?: element.text
    }

    override fun getLocationString(): String {
        return (element as? LuaFunctionCall)?.firstStringArgument.orEmpty()
    }

    override fun getIcon(unused: Boolean): Icon {
        return XMakeIcons.TARGET
    }

}

