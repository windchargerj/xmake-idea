package io.xmake.lang.annotation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.contextOfType
import com.intellij.psi.util.firstLeaf
import com.intellij.psi.util.siblings
import io.xmake.lang.highlight.XMakeLuaTextAttribute.XMAKE_LUA_DOMAIN_SCOPE
import io.xmake.lang.highlight.XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL
import io.xmake.lang.highlight.XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE
import io.xmake.lang.highlight.XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD
import io.xmake.lang.psi.XMakeLuaIdentifier
import io.xmake.lang.psi.lua.LuaFunctionCall
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.DomainScope.DomainType
import io.xmake.lang.psi.xmake.GlobalScope
import io.xmake.lang.psi.xmake.Scope
import io.xmake.utils.info.toCallee
import io.xmake.utils.info.xmakeInfo

class XMakeLuaFunctionAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {

        val apis = element.project.xmakeInfo.apis

        if (element is XMakeLuaIdentifier && element.parent is LuaFunctionCall) {

            val functions = when (val parentScope = element.contextOfType<Scope>()) {
                is GlobalScope -> {
                    apis.descriptionBuiltinApis + apis.descriptionScopeApis.toCallee()["target"].orEmpty()
                }
                is DomainScope -> {
                    apis.descriptionScopeApis.toCallee()[parentScope.type.text].orEmpty()
                }
                else -> emptyList()
            }

            if (element.name in functions) {
                holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(XMAKE_LUA_FUNCTION_CALL)
                    .create()
            } else if (element.siblings().singleOrNull { it is XMakeLuaIdentifier }?.text
                in DomainType.types.flatMap { listOf(it, it + "_end") }
            ) {
                holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(XMAKE_LUA_DOMAIN_SCOPE)
                    .create()
            } else if (element.nextSibling != null && element.name in DomainType.types) {
                holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                    .textAttributes(XMAKE_LUA_INSTANCE)
                    .create()
            } else if (element.prevSibling != null) {
                val methods = apis.scriptInstanceApis.filter {
                    it.startsWith(element.contextOfType<DomainScope>()?.firstLeaf()?.text ?: "")
                }.map {
                    it.substringAfter(":")
                }
                if (element.name in methods) {
                    holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
                        .textAttributes(XMAKE_LUA_INSTANCE_METHOD)
                        .create()
                }
            } else {
//                holder.newSilentAnnotation(HighlightSeverity.ERROR).create()
            }
        }
    }
}