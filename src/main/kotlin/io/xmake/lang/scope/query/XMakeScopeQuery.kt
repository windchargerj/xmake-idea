package io.xmake.lang.scope.query

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.xmake.lang.scope.builder.XMakePsiScopeInterpreter
import io.xmake.lang.scope.issue.ScopeIssue
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.model.XMakeState
import io.xmake.lang.scope.model.XMakeFileScopeModel

/**
 * Stable domain query boundary for consumers outside the scope package.
 *
 * New code should prefer this query object over reaching into builders, models,
 * or other domain internals directly unless it is extending the domain subsystem itself.
 */
object XMakeScopeQuery {

    fun domain(element: PsiElement): XMakeDomain =
        stateAt(element).domain

    fun model(file: PsiFile): XMakeFileScopeModel =
        read { modelInternal(file) }

    fun model(element: PsiElement): XMakeFileScopeModel =
        model(element.containingFile)

    fun regions(file: PsiFile): List<XMakeRegion> =
        model(file).regions

    fun regions(element: PsiElement): List<XMakeRegion> =
        model(element).regions

    fun regionsAt(element: PsiElement): List<XMakeRegion> =
        read {
            modelInternal(element.containingFile).regionsAt(offsetOf(element))
        }

    fun scriptSearchRoot(element: PsiElement): PsiElement =
        read {
            val file = element.containingFile ?: return@read element
            modelInternal(file).scriptSearchRootAt(offsetOf(element)) ?: file
        }

    fun enclosingConfigurationRegion(element: PsiElement): XMakeRegion? =
        read {
            modelInternal(element.containingFile).enclosingRegion(offsetOf(element)) { it.domain is XMakeDomain.Configuration }
        }

    fun issues(file: PsiFile): List<ScopeIssue> =
        model(file).issues

    fun stateAt(element: PsiElement): XMakeState =
        read { inferStateInternal(element) }

    fun stateAt(file: PsiFile, offset: Int): XMakeState =
        read { modelInternal(file).stateAt(offset.coerceIn(0, file.textLength)) }

    fun issuesAt(element: PsiElement): List<ScopeIssue> =
        stateAt(element).issues

    private fun inferStateInternal(element: PsiElement): XMakeState =
        modelInternal(element.containingFile).stateAt(offsetOf(element))

    private fun <T> read(action: () -> T): T =
        ReadAction.compute<T, RuntimeException> { action() }

    private fun modelInternal(file: PsiFile): XMakeFileScopeModel =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                XMakePsiScopeInterpreter.interpret(file),
                file
            )
        }

    private fun offsetOf(element: PsiElement): Int =
        element.textRange.startOffset
}
