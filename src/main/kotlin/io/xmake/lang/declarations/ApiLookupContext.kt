package io.xmake.lang.declarations

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaStatement

internal object ApiLookupContext {

    fun at(element: PsiElement): ApiLookupView =
        ApiLookupView.fromState(XMakeScopeQuery.stateAt(element))

    fun forIdentifier(identifier: XMakeLuaIdentifier): ApiLookupView =
        structuralEntryParentContext(identifier) ?: at(identifier)

    private fun structuralEntryParentContext(identifier: XMakeLuaIdentifier): ApiLookupView? {
        val name = identifier.name ?: return null
        if (!PsiPredicates.isFunctionCall(identifier) ||
            !XMakeDescriptionDomainRules.isStructuralEntry(name)
        ) {
            return null
        }

        val statement = PsiTreeUtil.getParentOfType(identifier, LuaStatement::class.java) ?: return null
        val statementStart = statement.textRange.startOffset
        val model = XMakeScopeQuery.model(identifier)
        val regionsAtIdentifier = model.regionsAt(identifier.textRange.startOffset)

        if (regionsAtIdentifier.none { region -> region.isOpenedByStructuralEntry(name, statementStart) }) {
            return null
        }

        val parentRegion = regionsAtIdentifier
            .firstOrNull { region -> !region.isOpenedByStructuralEntry(name, statementStart) }
            ?: return ApiLookupView.DESCRIPTION_GLOBAL_ROOT

        return ApiLookupView(
            domain = parentRegion.domain,
            root = parentRegion.root
        )
    }

    private fun XMakeRegion.isOpenedByStructuralEntry(
        entryName: String,
        statementStart: Int
    ): Boolean {
        if (startOffset != statementStart) {
            return false
        }

        val entryDomainType = XMakeDescriptionDomainRules.configurationDomainTypeForEntry(entryName)
        if (domain is XMakeDomain.Configuration && domain.type == entryDomainType) {
            return true
        }

        return XMakeDescriptionDomainRules.isNamespaceEntry(entryName) &&
            domain is XMakeDomain.Description &&
            root is XMakeRoot.Namespace
    }
}
