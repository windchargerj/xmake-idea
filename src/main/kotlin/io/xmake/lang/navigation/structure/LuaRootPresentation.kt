package io.xmake.lang.navigation.structure

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiFile
import io.xmake.icons.XMakeIcons
import javax.swing.Icon

class LuaRootPresentation(val element: PsiFile) : ItemPresentation {
    override fun getIcon(unused: Boolean): Icon {
        return XMakeIcons.FILE
    }

    override fun getPresentableText(): String {
        return element.virtualFile.nameWithoutExtension
    }

    override fun getLocationString(): String {
        return element.toString()
    }
}

