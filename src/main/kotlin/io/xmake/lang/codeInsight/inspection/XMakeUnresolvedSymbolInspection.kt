package io.xmake.lang.codeInsight.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import io.xmake.lang.analysis.model.IdentifierResolutionStatus
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeUnresolvedSymbolInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element !is XMakeLuaIdentifier) return

                val analysis = IdentifierAnalysis.analyze(element)
                val message = when (analysis.resolutionStatus) {
                    IdentifierResolutionStatus.UNRESOLVED_VARIABLE -> unresolvedVariableMessage(element)
                    IdentifierResolutionStatus.UNRESOLVED_FUNCTION -> unresolvedFunctionMessage(element)
                    IdentifierResolutionStatus.RESOLVED -> null
                } ?: return

                holder.registerProblem(element, message, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
            }
        }
    }

    private fun unresolvedVariableMessage(element: XMakeLuaIdentifier): String {
        val name = element.name
        return "Unresolved variable '$name'"
    }

    private fun unresolvedFunctionMessage(element: XMakeLuaIdentifier): String {
        val name = element.name
        return "Unresolved function '$name'"
    }
}

