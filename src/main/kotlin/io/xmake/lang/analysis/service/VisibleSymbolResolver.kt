package io.xmake.lang.analysis.service

import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.lua.LuaSymbolResolver
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbol
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object VisibleSymbolResolver {

    fun resolve(
        element: XMakeLuaIdentifier,
        context: ApiLookupView = ApiLookupContext.forIdentifier(element)
    ): VisibleSymbol? {
        if (PsiPredicates.isGotoLabelReference(element)) {
            LuaSymbolResolver.resolveLabelReference(element)?.let { return VisibleSymbol.Local(it, inferredType = null) }
            return null
        }
        if (PsiPredicates.isLabel(element)) {
            return VisibleSymbol.Local(element, inferredType = null)
        }

        (LuaSymbolResolver.resolveLocal(element) as? XMakeLuaIdentifier)?.let { declaration ->
            return VisibleSymbol.Local(declaration, inferredType = null)
        }

        LuaSymbolResolver.resolveSynthetic(element)?.let { synthetic ->
            return synthetic.toVisibleSymbol()
        }

        return resolveBuiltinSymbol(
            projectApi = XMakeApi.getInstance(element.project),
            name = element.name ?: return null,
            context = context,
            file = element.containingFile as? XMakeLuaFile,
            place = element
        )
    }

    fun find(
        place: PsiElement,
        name: String,
        beforeOffset: Int = place.textOffset,
        context: ApiLookupView
    ): VisibleSymbol? {
        LuaSymbolResolver.findVisibleDeclaration(place, name, beforeOffset)?.let { declaration ->
            return VisibleSymbol.Local(declaration, inferredType = null)
        }

        LuaSymbolResolver.findVisibleSynthetic(place, name)?.let { synthetic ->
            return synthetic.toVisibleSymbol()
        }

        return resolveBuiltinSymbol(
            projectApi = XMakeApi.getInstance(place.project),
            name = name,
            context = context,
            file = place.containingFile as? XMakeLuaFile,
            place = place
        )
    }

    private fun resolveBuiltinSymbol(
        projectApi: XMakeApi,
        name: String,
        context: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement?
    ): VisibleSymbol? {
        projectApi.resolveVisibleModulePath(name, context, file, place)
            ?.takeIf { projectApi.isBuiltinModulePath(it, context) || projectApi.isExtensionModulePath(it) }
            ?.let { modulePath ->
                return VisibleSymbol.BuiltinModule(
                    modulePath = modulePath,
                    inferredType = XMakeType.Module(modulePath, context)
                )
            }

        return projectApi.findUnqualifiedApi(name, context, file, place)?.let(VisibleSymbol::BuiltinApi)
    }

    private fun XMakeSyntheticSymbol.toVisibleSymbol(): VisibleSymbol =
        when (this) {
            is XMakeSyntheticSymbol.ImportedModuleSymbol -> VisibleSymbol.ImportedModule(this)
            is XMakeSyntheticSymbol.InheritedApiSymbol -> VisibleSymbol.InheritedApi(this)
        }
}
