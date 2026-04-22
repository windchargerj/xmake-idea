package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer

data class ImportIssue(
    val kind: Kind,
    val message: String,
    internal val declarationPointer: SmartPsiElementPointer<PsiElement>? = null
) {
    enum class Kind {
        DECLARATION_PARSE_FAILED,
        DECLARATION_COLLECTION_FAILED,
        ADD_IMPORT_PARSE_FAILED,
        ADD_IMPORT_COLLECTION_FAILED,
        MODULE_EXPORTS_RESOLUTION_FAILED
    }

    val declarationElement: PsiElement?
        get() = declarationPointer?.element

    companion object {
        internal fun fromElement(kind: Kind, message: String, element: PsiElement?): ImportIssue =
            ImportIssue(
                kind = kind,
                message = message,
                declarationPointer = element?.let(SmartPointerManager::createPointer)
            )
    }
}
