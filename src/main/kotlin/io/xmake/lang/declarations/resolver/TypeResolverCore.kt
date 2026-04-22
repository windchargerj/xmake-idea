package io.xmake.lang.declarations.resolver

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.syntax.psi.XMakeLuaFile

interface TypeResolver {

    fun resolveType(identifier: String, context: ApiLookupView): XMakeType?

    fun resolveMemberReturnType(receiverType: XMakeType, memberName: String, separator: String = ":"): XMakeType?

    fun isModulePath(path: String, context: ApiLookupView): Boolean

    fun isModulePath(path: String, context: ApiLookupView, file: XMakeLuaFile?): Boolean

    fun isModulePath(path: String, context: ApiLookupView, file: XMakeLuaFile?, place: PsiElement?): Boolean
}
