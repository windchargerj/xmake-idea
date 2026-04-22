package io.xmake.lang.resolution.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.util.ProcessingContext
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.resolution.XMakeDeclarationTargets
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> =
        (element as? XMakeLuaIdentifier)?.let(::createReferences) ?: PsiReference.EMPTY_ARRAY
}

internal fun createReferences(element: XMakeLuaIdentifier): Array<PsiReference> =
    if (
        PsiPredicates.isLabel(element) ||
        PsiPredicates.isDeclaration(element) ||
        PsiPredicates.isFunctionDeclarationName(element)
    ) {
        PsiReference.EMPTY_ARRAY
    } else {
        arrayOf(XMakePsiReference(element))
    }

class XMakePsiReference(
    private val element: XMakeLuaIdentifier
) : PsiReference {

    override fun getElement(): PsiElement = element

    override fun getRangeInElement(): TextRange = TextRange.create(0, element.textLength)

    override fun getCanonicalText(): String = element.name ?: element.text

    override fun isReferenceTo(element: PsiElement): Boolean {
        return resolve() == element
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val declarationTarget = XMakeDeclarationTargets.resolve(element)
        val analysis = IdentifierAnalysis.analyze(element)
        return when {
            declarationTarget != null && !XMakeDeclarationTargets.isSyntheticApiDeclaration(declarationTarget) ->
                this.element.setName(newElementName)

            declarationTarget == null && analysis.isReferenceCandidate ->
                this.element.setName(newElementName)

            else -> this.element
        }
    }

    override fun bindToElement(element: PsiElement): PsiElement =
        (element as? XMakeLuaIdentifier)?.let { this.element.setName(it.name) } ?: this.element

    override fun isSoft(): Boolean = true

    override fun resolve(): PsiElement? {
        return XMakeDeclarationTargets.resolve(element)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
