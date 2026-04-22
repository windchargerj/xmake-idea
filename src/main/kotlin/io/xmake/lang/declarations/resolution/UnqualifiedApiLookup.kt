package io.xmake.lang.declarations.resolution

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.import.ImportLookup
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Unqualified-name lookup that combines inherited import exposure lookup with the
 * indexed built-in API catalog.
 */
internal class UnqualifiedApiLookup(
    private val lookup: ApiIndex,
    private val imports: ImportLookup
) {
    fun findUnqualifiedApi(name: String, context: ApiLookupView, file: XMakeLuaFile? = null, place: PsiElement? = null): ApiModel? {
        if (context.domain is XMakeDomain.Script && file != null) {
            imports.findInheritedApiExposures(file, name, place)?.primary?.api?.let { return it }
        }

        return lookup.findByName(name)
            .firstOrNull { api ->
                api.isAvailableIn(context) &&
                    !api.isModuleApi &&
                    !api.isInstanceApi
            }
    }
}
