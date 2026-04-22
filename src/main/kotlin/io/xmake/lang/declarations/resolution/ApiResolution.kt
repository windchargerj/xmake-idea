package io.xmake.lang.declarations.resolution

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.model.ApiModel

/**
 * Semantic API resolution payload.
 *
 * [virtualElement] is only populated when the resolved target can be exposed
 * as a synthetic PSI anchor.
 */
data class ApiResolution(
    val api: ApiModel,
    val virtualElement: PsiElement? = null
)
