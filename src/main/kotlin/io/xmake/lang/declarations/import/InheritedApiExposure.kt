package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.model.ApiModel

/**
 * Resolution metadata for an API made visible through `inherit(...)`.
 */
data class InheritedApiExposure(
    val api: ApiModel,
    val declarationElement: PsiElement?
)
