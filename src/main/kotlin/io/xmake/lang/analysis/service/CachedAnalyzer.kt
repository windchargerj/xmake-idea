package io.xmake.lang.analysis.service

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.xmake.lang.declarations.source.ApiService

internal abstract class CachedAnalyzer<V : Any?> {

    protected abstract val cacheKey: Key<CachedValue<V>>

    protected fun getOrPut(element: PsiElement, compute: () -> V): V {
        return CachedValuesManager.getCachedValue(element, cacheKey) {
            CachedValueProvider.Result.create(
                compute(),
                PsiModificationTracker.MODIFICATION_COUNT,
                ApiService.getInstance(element.project).modificationTracker
            )
        }
    }
}
