package io.xmake.lang.analysis.xmake

import io.xmake.lang.analysis.model.IdentifierStructuralKind
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain

internal object XMakeStructuralCallClassifier {

    fun classify(name: String, currentScope: XMakeDomain): IdentifierStructuralKind? {
        if (currentScope is XMakeDomain.Script) {
            return null
        }

        return when {
            XMakeDescriptionDomainRules.isNamespaceEntry(name) ->
                IdentifierStructuralKind.NAMESPACE_ENTRY

            XMakeDescriptionDomainRules.isNamespaceEnd(name) ->
                IdentifierStructuralKind.NAMESPACE_END

            XMakeDescriptionDomainRules.isConfigurationDomainEntry(name) ->
                IdentifierStructuralKind.CONFIGURATION_DOMAIN_ENTRY

            XMakeDescriptionDomainRules.isConfigurationDomainEnd(name) ->
                IdentifierStructuralKind.CONFIGURATION_DOMAIN_END

            else -> null
        }
    }
}
