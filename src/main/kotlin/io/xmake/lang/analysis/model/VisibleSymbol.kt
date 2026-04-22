package io.xmake.lang.analysis.model

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbol
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal sealed interface VisibleSymbol {
    val declarationElement: PsiElement?
    val inferredType: XMakeType?

    data class Local(
        val declaration: XMakeLuaIdentifier,
        override val inferredType: XMakeType?
    ) : VisibleSymbol {
        override val declarationElement: PsiElement
            get() = declaration
    }

    data class ImportedModule(
        val symbol: XMakeSyntheticSymbol.ImportedModuleSymbol
    ) : VisibleSymbol {
        override val declarationElement: PsiElement?
            get() = symbol.declarationElement

        override val inferredType: XMakeType?
            get() = symbol.inferredType
    }

    data class InheritedApi(
        val symbol: XMakeSyntheticSymbol.InheritedApiSymbol
    ) : VisibleSymbol {
        val api: ApiModel
            get() = symbol.api

        override val declarationElement: PsiElement?
            get() = symbol.declarationElement

        override val inferredType: XMakeType?
            get() = symbol.inferredType
    }

    data class Synthetic(
        val symbol: XMakeSyntheticSymbol,
        override val inferredType: XMakeType?
    ) : VisibleSymbol {
        override val declarationElement: PsiElement?
            get() = symbol.declarationElement
    }

    data class BuiltinApi(
        val api: ApiModel
    ) : VisibleSymbol {
        override val declarationElement: PsiElement? = null
        override val inferredType: XMakeType? = null
    }

    data class BuiltinModule(
        val modulePath: String,
        override val inferredType: XMakeType.Module
    ) : VisibleSymbol {
        override val declarationElement: PsiElement? = null
    }
}
