package io.xmake.lang.navigation.structure

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiFile

class LuaStructureViewRootElement(element: PsiFile) : LuaStructureViewElement(element) {
    override fun getPresentation(): ItemPresentation {
        return LuaRootPresentation(element as PsiFile)
    }
}

