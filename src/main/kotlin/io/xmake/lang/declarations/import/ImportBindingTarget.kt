package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement

/**
 * Target metadata for a bound import name.
 */
data class ImportBindingTarget(
    val module: ImportedModuleView,
    val declarationElement: PsiElement?,
    val origin: ImportBindingOrigin,
    val isReturnCapture: Boolean = false
)
