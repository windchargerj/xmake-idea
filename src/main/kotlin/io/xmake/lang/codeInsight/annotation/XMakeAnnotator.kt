package io.xmake.lang.codeInsight.annotation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is XMakeLuaIdentifier) return

        val analysis = IdentifierAnalysis.analyze(element)
        XMakeScopeErrorAnnotationStage.annotate(element, analysis.validationError, holder)

        val highlightKind = analysis.highlightKind ?: return
        XMakeSemanticHighlightAnnotationStage.annotate(element, highlightKind, holder)
    }
}
