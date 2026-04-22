package io.xmake.lang.declarations

import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.XMakeState
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.configurationDomainType

data class ApiLookupView(
    val domain: XMakeDomain,
    val root: XMakeRoot = XMakeRoot.Global
) {
    val configurationDomainType: XMakeConfigurationDomainType?
        get() = domain.configurationDomainType

    val isNamespaceRoot: Boolean
        get() = root is XMakeRoot.Namespace

    fun withDomain(domain: XMakeDomain): ApiLookupView =
        if (this.domain == domain) this else ApiLookupView(domain = domain, root = root)

    companion object {
        val DESCRIPTION_GLOBAL_ROOT = ApiLookupView(XMakeDomain.Description)

        val SCRIPT_GLOBAL_ROOT = ApiLookupView(XMakeDomain.Script)

        fun configuration(
            domainType: XMakeConfigurationDomainType,
            root: XMakeRoot = XMakeRoot.Global
        ): ApiLookupView =
            ApiLookupView(XMakeDomain.Configuration(domainType), root)

        fun fromState(state: XMakeState): ApiLookupView =
            ApiLookupView(
                domain = state.domain,
                root = state.root
            )
    }
}
