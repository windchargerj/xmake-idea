package io.xmake.lang.codeInsight.annotation

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import io.xmake.lang.analysis.model.ValidationError
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

object XMakeScopeErrorAnnotationStage {

    fun annotate(
        element: XMakeLuaIdentifier,
        validationError: ValidationError?,
        holder: AnnotationHolder
    ) {
        val error = validationError ?: return
        val target = (error.errorElement ?: element) as PsiElement
        holder.newAnnotation(HighlightSeverity.ERROR, error.message)
            .range(target)
            .create()
    }
}

