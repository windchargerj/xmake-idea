package io.xmake.lang.declarations.synthetic

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.import.ImportedObjectKind
import io.xmake.lang.declarations.import.ImportedModuleView
import io.xmake.lang.declarations.model.XMakeType

sealed interface XMakeSyntheticSymbol {
    val name: String
    val declarationElement: PsiElement?
    val inferredType: XMakeType?
    val origin: Origin

    enum class Origin {
        IMPORT,
        ADD_IMPORTS,
        INHERIT
    }

    data class ImportedModuleSymbol(
        override val name: String,
        val module: ImportedModuleView,
        override val declarationElement: PsiElement?,
        override val origin: Origin
    ) : XMakeSyntheticSymbol {
        override val inferredType: XMakeType =
            when (module.kind) {
                ImportedObjectKind.MODULE -> XMakeType.Module(
                    module.identifier,
                    ApiLookupView.SCRIPT_GLOBAL_ROOT,
                    hasUnknownMembers = module.hasUnknownMembers
                )
                ImportedObjectKind.CALLABLE -> XMakeType.Function()
                ImportedObjectKind.DIRECTORY,
                ImportedObjectKind.NATIVE_BINARY,
                ImportedObjectKind.NATIVE_SHARED -> XMakeType.Unknown
            }
    }

    data class InheritedApiSymbol(
        override val name: String,
        val api: ApiModel,
        override val declarationElement: PsiElement?
    ) : XMakeSyntheticSymbol {
        override val inferredType: XMakeType? = null
        override val origin: Origin = Origin.INHERIT
    }
}
