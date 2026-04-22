package io.xmake.lang.analysis.lua

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaStatement

internal val XMakeLuaIdentifier.isNestedCallQualifier: Boolean
    get() {
        val chain = LuaCallChainResolver.resolve(this) ?: return false
        return chain.targetIdentifier !== this
    }

internal val XMakeLuaIdentifier.isEditorRecoveryCallHead: Boolean
    get() {
        if (PsiPredicates.isFunctionCall(this) ||
            PsiPredicates.isFunctionDefinition(this) ||
            PsiPredicates.isFunctionDeclarationName(this) ||
            PsiPredicates.isAssignmentTarget(this) ||
            PsiPredicates.isLocalVariable(this) ||
            PsiPredicates.isParameter(this) ||
            PsiPredicates.isLabel(this) ||
            PsiPredicates.isGotoLabelReference(this) ||
            PsiPredicates.isTableKey(this) ||
            PsiPredicates.isTableFieldAccess(this)
        ) {
            return false
        }

        val statement = PsiTreeUtil.getParentOfType(this, LuaStatement::class.java) ?: return false
        if (LuaPsiVisibleLeaves.firstWithin(statement) != this) {
            return false
        }

        return LuaPsiVisibleLeaves.nextWithin(this, statement) == null
    }
