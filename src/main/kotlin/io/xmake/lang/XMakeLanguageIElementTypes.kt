package io.xmake.lang

import com.intellij.psi.tree.IElementType

class XMakeLanguageIElementTypes {
    abstract class XMakeLuaIElementType(debugName: String) : IElementType(debugName, XMakeLuaLanguage.INSTANCE)

    class ScriptScopeType : XMakeLuaIElementType("ScriptScopeType")
    class DescriptionScopeType(val typeName: String?) : XMakeLuaIElementType("DescriptionScopeType")
    class NamespaceScopeType(val namespace: String) : XMakeLuaIElementType("NamespaceScopeType")
}