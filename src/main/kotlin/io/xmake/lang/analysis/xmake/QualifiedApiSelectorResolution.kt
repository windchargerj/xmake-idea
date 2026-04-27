package io.xmake.lang.analysis.xmake

import com.intellij.openapi.application.ReadAction
import io.xmake.lang.analysis.lua.LuaCallChainResolver
import io.xmake.lang.analysis.lua.LuaMemberAccessResolver
import io.xmake.lang.analysis.lua.LuaTypeInference
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.declarations.resolver.TypeResolver
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal fun XMakeLuaIdentifier.qualifiedApiSelector(context: ApiLookupView): QualifiedApiSelector? =
    ReadAction.compute<QualifiedApiSelector?, RuntimeException> {
        val chain = LuaCallChainResolver.resolve(this) ?: return@compute null
        if (chain.targetIdentifier !== this) return@compute null

        LuaMemberAccessResolver.findDirectMemberAccess(this)?.let { memberAccess ->
            when (memberAccess.separator) {
                ":" -> {
                    val receiverType = LuaTypeInference.inferType(memberAccess.receiver, context) as? XMakeType.Instance
                    if (receiverType != null) {
                        return@compute QualifiedApiSelector.InstanceMethod(
                            instanceType = receiverType.typeName,
                            methodName = text
                        )
                    }
                }

                "." -> {
                    val receiverType = LuaTypeInference.inferType(memberAccess.receiver, context) as? XMakeType.Module
                    if (receiverType != null) {
                        return@compute QualifiedApiSelector.ModuleFunction(
                            modulePath = receiverType.path,
                            functionName = text
                        )
                    }
                }
            }
        }

        val pathSegments = chain.pathSegments
        if (pathSegments.size < 2) return@compute null
        if (chain.identifiers.firstOrNull()?.isLocalShadow(context) == true) return@compute null

        val file = this.containingFile as? XMakeLuaFile
        val typeResolver = project.typeResolver
        if (typeResolver != null) {
            for (i in pathSegments.size downTo 1) {
                val potentialModulePath = pathSegments.take(i).joinToString(".")
                if (typeResolver.isModulePath(potentialModulePath, context, file, this)) {
                    val memberPath = pathSegments.drop(i).joinToString(".")
                    if (memberPath.isNotEmpty()) {
                        return@compute QualifiedApiSelector.ModuleFunction(
                            modulePath = potentialModulePath,
                            functionName = memberPath
                        )
                    }
                }
            }
        }

        null
    }

private val com.intellij.openapi.project.Project.typeResolver: TypeResolver?
    get() = try {
        xmakeApi.typeResolver
    } catch (e: Exception) {
        null
    }

private fun XMakeLuaIdentifier.isLocalShadow(context: ApiLookupView): Boolean =
    VisibleSymbolResolver.resolve(this, context) is VisibleSymbol.Local
