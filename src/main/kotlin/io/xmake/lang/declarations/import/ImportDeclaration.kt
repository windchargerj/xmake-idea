package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

sealed interface ImportDeclaration {
    val modulePath: String
    val declarationElement: PsiElement?
}

data class ModuleImportDeclaration(
    val spec: ImportSpec,
    val variableAlias: String? = null,
    internal val declarationPointer: SmartPsiElementPointer<PsiElement>? = null
) : ImportDeclaration {
    override val modulePath: String
        get() = spec.modulePath

    override val declarationElement: PsiElement?
        get() = declarationPointer?.element
}

data class AddImportDeclaration(
    override val modulePath: String,
    internal val declarationPointer: SmartPsiElementPointer<PsiElement>? = null
) : ImportDeclaration {
    override val declarationElement: PsiElement?
        get() = declarationPointer?.element
}
