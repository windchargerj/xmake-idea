package io.xmake.lang.declarations

import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain

object ApiAvailabilityPolicy {

    fun isAvailable(api: ApiModel, context: ApiLookupView): Boolean {
        if (api.isNamespaceEnd) {
            return context.domain is XMakeDomain.Description && context.isNamespaceRoot
        }
        if (api.type is ApiType.DescriptionApi.GlobalInterface &&
            context.domain is XMakeDomain.Configuration &&
            XMakeDescriptionDomainRules.isGlobalInterfaceVisibleInConfigurationDomain(api.name, context.domain.type)
        ) {
            return true
        }
        if (context.domain is XMakeDomain.Description &&
            api.type is ApiType.DescriptionApi.ConfigurationItem &&
            api.configurationDomainType == XMakeConfigurationDomainType.TARGET
        ) {
            return true
        }
        if (context.domain is XMakeDomain.Configuration &&
            (api.type is ApiType.DescriptionApi.ConfigurationItem ||
                api.type is ApiType.DescriptionApi.ConfigurationDomainEnd) &&
            api.configurationDomainType != context.domain.type
        ) {
            return false
        }
        return api.availability.isAvailableIn(context.domain)
    }

    fun describeContext(context: ApiLookupView): String =
        when (val domain = context.domain) {
            is XMakeDomain.Description ->
                if (context.isNamespaceRoot) DESCRIPTION_NAMESPACE_ROOT else DESCRIPTION_GLOBAL_ROOT

            is XMakeDomain.Configuration -> configurationDomain(domain.type)
            is XMakeDomain.Script -> SCRIPT_DOMAIN
        }

    fun describeAvailabilities(apis: Iterable<ApiModel>): List<String> =
        apis.flatMapTo(linkedSetOf(), ::describeAvailability).toList()

    private fun describeAvailability(api: ApiModel): List<String> {
        val descriptions = linkedSetOf<String>()
        if (api.isNamespaceEnd) {
            descriptions += DESCRIPTION_NAMESPACE_ROOT
            return descriptions.toList()
        }
        if (api.type is ApiType.DescriptionApi.ConfigurationItem &&
            api.configurationDomainType == XMakeConfigurationDomainType.TARGET
        ) {
            descriptions += DESCRIPTION_GLOBAL_ROOT
            descriptions += DESCRIPTION_NAMESPACE_ROOT
            descriptions += configurationDomain(api.configurationDomainType)
            return descriptions.toList()
        }
        if (api.type is ApiType.DescriptionApi.ConfigurationItem ||
            api.type is ApiType.DescriptionApi.ConfigurationDomainEnd
        ) {
            descriptions += configurationDomain(api.configurationDomainType)
            return descriptions.toList()
        }

        if (api.availability.isDescriptionAvailable) {
            descriptions += DESCRIPTION_GLOBAL_ROOT
        }
        if (api.availability.isConfigurationAvailable) {
            descriptions += configurationDomain()
        }
        if (api.availability.isScriptAvailable) {
            descriptions += SCRIPT_DOMAIN
        }
        if (api.type is ApiType.DescriptionApi.GlobalInterface) {
            XMakeConfigurationDomainType.entries
                .filter { domainType ->
                    XMakeDescriptionDomainRules.isGlobalInterfaceVisibleInConfigurationDomain(api.name, domainType)
                }
                .forEach { domainType ->
                    descriptions += configurationDomain(domainType)
                }
        }
        return descriptions.toList()
    }

    private fun configurationDomain(domainType: XMakeConfigurationDomainType? = null): String =
        "configuration domain (${domainType?.toKeyword() ?: "all types"})"

    private const val DESCRIPTION_GLOBAL_ROOT = "description domain (global root)"
    private const val DESCRIPTION_NAMESPACE_ROOT = "description domain (namespace root)"
    private const val SCRIPT_DOMAIN = "script domain"
}
