package io.xmake.lang.scope.model

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import io.xmake.lang.scope.issue.ScopeIssue

data class XMakeFileScopeModel(
    val regions: List<XMakeRegion>,
    val scriptSearchRoots: List<ScriptSearchRoot> = emptyList(),
    val issues: List<ScopeIssue> = emptyList()
) {
    private val fileEndOffset: Int = regions.maxOfOrNull { it.endOffset } ?: 0

    fun regionsAt(offset: Int): List<XMakeRegion> =
        regions.asSequence()
            .filter { it.containsAt(offset) }
            .sortedWith(REGION_ORDER)
            .toList()

    fun enclosingRegion(offset: Int, predicate: (XMakeRegion) -> Boolean): XMakeRegion? =
        regionsAt(offset).firstOrNull(predicate)

    fun scriptRegionAt(offset: Int): XMakeRegion? =
        regions
            .asSequence()
            .filter { it.domain is XMakeDomain.Script && it.containsAt(offset) }
            .minWithOrNull(compareBy<XMakeRegion> { it.range.length }.thenByDescending { it.range.startOffset })

    fun scriptSearchRootAt(offset: Int): PsiElement? =
        scriptSearchRoots
            .asSequence()
            .filter { it.containsAt(offset) }
            .minWithOrNull(compareBy<ScriptSearchRoot> { it.range.length }.thenByDescending { it.range.startOffset })
            ?.resolve()

    fun regionAt(offset: Int): XMakeRegion =
        regionsAt(offset).firstOrNull()
            ?: XMakeRegion(
                domain = XMakeDomain.Description,
                root = XMakeRoot.Global,
                range = TextRange(offset, offset),
                source = XMakeRegion.Source.PSI
            )

    fun stateAt(offset: Int): XMakeState =
        XMakeState(
            offset = offset,
            region = regionAt(offset),
            issues = issuesAt(offset)
        )

    fun issuesAt(offset: Int): List<ScopeIssue> =
        issues.filter { it.contains(offset) }

    private fun XMakeRegion.containsAt(offset: Int): Boolean =
        contains(offset) || (offset == fileEndOffset && endOffset == offset)

    private fun ScriptSearchRoot.containsAt(offset: Int): Boolean =
        contains(offset) || (offset == fileEndOffset && range.endOffset == offset)

    companion object {
        private val REGION_ORDER =
            compareBy<XMakeRegion>({ it.range.length }, { domainRank(it.domain) }, { rootRank(it.root) })

        private fun domainRank(domain: XMakeDomain): Int = when (domain) {
            is XMakeDomain.Script -> 0
            is XMakeDomain.Configuration -> 1
            is XMakeDomain.Description -> 2
        }

        private fun rootRank(root: XMakeRoot): Int = when (root) {
            is XMakeRoot.Namespace -> 0
            is XMakeRoot.Global -> 1
        }
    }
}

class ScriptSearchRoot private constructor(
    val range: TextRange,
    private val pointer: SmartPsiElementPointer<PsiElement>
) {
    fun contains(offset: Int): Boolean =
        if (range.length == 0) {
            offset == range.startOffset
        } else {
            offset in range.startOffset until range.endOffset
        }

    fun resolve(): PsiElement? = pointer.element?.takeIf { it.isValid }

    companion object {
        fun from(range: TextRange, element: PsiElement): ScriptSearchRoot =
            ScriptSearchRoot(
                range = range,
                pointer = SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(element)
            )
    }
}
