package io.xmake.lang.editor.template

import com.intellij.codeInsight.template.EverywhereContextType
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiFile
import io.xmake.lang.syntax.XMakeLuaLanguage

class XMakeLuaLiveTemplateContext : TemplateContextType(
    "XMAKE_LUA",
    "XMake Lua",
    EverywhereContextType::class.java
) {
    override fun isInContext(file: PsiFile, offset: Int): Boolean =
        file.language.isKindOf(XMakeLuaLanguage.INSTANCE)
}
