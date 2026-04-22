package io.xmake.lang.codeInsight.annotation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.model.IdentifierHighlightKind
import io.xmake.lang.editor.highlighting.semantic.IdentifierHighlightTextAttributes
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object XMakeSemanticHighlightAnnotationStage {
    fun annotate(element: XMakeLuaIdentifier, highlightKind: IdentifierHighlightKind, holder: AnnotationHolder) {
        holder.highlight(element, IdentifierHighlightTextAttributes.get(highlightKind))
    }

    private fun AnnotationHolder.highlight(element: XMakeLuaIdentifier, textAttributes: TextAttributesKey) {
        newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
            .range(element as PsiElement)
            .textAttributes(textAttributes)
            .create()
    }
}
