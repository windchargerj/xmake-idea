package io.xmake.lang.declarations.model

import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.ApiAvailabilityPolicy
import io.xmake.lang.scope.model.ApiAvailability
import io.xmake.lang.scope.model.XMakeConfigurationDomainType

data class ApiModel(
    val fullName: String,
    val name: String,
    val type: ApiType,
    val availability: ApiAvailability
) {
    val isModuleApi: Boolean
        get() = type.isModuleApi

    val isScriptApi: Boolean
        get() = availability.isScriptAvailable && !availability.isDescriptionAvailable

    val isInstanceApi: Boolean
        get() = type is ApiType.ScriptApi.InstanceApi

    val isConfigurationItem: Boolean
        get() = type is ApiType.DescriptionApi.ConfigurationItem

    val configurationDomainType: XMakeConfigurationDomainType?
        get() = type.domainType

    val modulePath: String?
        get() = type.modulePath

    val instanceType: String?
        get() = type.instanceType

    val isNamespaceEnd: Boolean
        get() = type is ApiType.DescriptionApi.NamespaceEnd

    val displayText: String
        get() = type.displayText

    fun isAvailableIn(context: ApiLookupView): Boolean = ApiAvailabilityPolicy.isAvailable(this, context)
}
