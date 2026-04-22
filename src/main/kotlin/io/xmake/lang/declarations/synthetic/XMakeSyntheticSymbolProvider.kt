package io.xmake.lang.declarations.synthetic

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.import.ImportBindingOrigin
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile

object XMakeSyntheticSymbolProvider {

    private val contributors: List<XMakeSyntheticSymbolContributor> = listOf(
        ImportedModuleSymbolContributor,
        InheritedApiSymbolContributor
    )

    fun resolve(
        api: XMakeApi,
        file: XMakeLuaFile?,
        place: PsiElement?,
        name: String,
        context: ApiLookupView
    ): XMakeSyntheticSymbol? {
        if (file == null || name.isBlank()) {
            return null
        }

        return contributors.firstNotNullOfOrNull { contributor ->
            contributor.resolve(api, file, place, name, context)
        }
    }
}

private fun interface XMakeSyntheticSymbolContributor {
    fun resolve(
        api: XMakeApi,
        file: XMakeLuaFile,
        place: PsiElement?,
        name: String,
        context: ApiLookupView
    ): XMakeSyntheticSymbol?
}

private object ImportedModuleSymbolContributor : XMakeSyntheticSymbolContributor {
    override fun resolve(
        api: XMakeApi,
        file: XMakeLuaFile,
        place: PsiElement?,
        name: String,
        context: ApiLookupView
    ): XMakeSyntheticSymbol? {
        if (context.domain !is XMakeDomain.Script) {
            return null
        }
        val binding = api.imports.findReceiverBindingTargets(file, name, place)?.primary ?: return null
        val origin = when (binding.origin) {
            ImportBindingOrigin.IMPORT -> XMakeSyntheticSymbol.Origin.IMPORT
            ImportBindingOrigin.ADD_IMPORTS -> XMakeSyntheticSymbol.Origin.ADD_IMPORTS
        }
        return XMakeSyntheticSymbol.ImportedModuleSymbol(
            name = name,
            module = binding.module,
            declarationElement = binding.declarationElement,
            origin = origin
        )
    }
}

private object InheritedApiSymbolContributor : XMakeSyntheticSymbolContributor {
    override fun resolve(
        api: XMakeApi,
        file: XMakeLuaFile,
        place: PsiElement?,
        name: String,
        context: ApiLookupView
    ): XMakeSyntheticSymbol? {
        if (context.domain !is XMakeDomain.Script) {
            return null
        }
        val inheritedBinding = api.imports.findInheritedApiExposures(file, name, place)?.primary ?: return null
        return XMakeSyntheticSymbol.InheritedApiSymbol(name, inheritedBinding.api, inheritedBinding.declarationElement)
    }
}
