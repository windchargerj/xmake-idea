package io.xmake.lang.declarations.model

import io.xmake.lang.scope.model.XMakeConfigurationDomainType

sealed interface ApiType {
    val displayText: String

    val modulePath: String?
        get() = when (this) {
            is DescriptionApi.BuiltinModuleApi -> this.modulePath
            is ScriptApi.BuiltinModuleApi -> this.modulePath
            is ScriptApi.ExtensionModuleApi -> this.modulePath
            else -> null
        }

    val domainType: XMakeConfigurationDomainType?
        get() = when (this) {
            is DescriptionApi.ConfigurationDomainEntry -> this.domainType
            is DescriptionApi.ConfigurationDomainEnd -> this.domainType
            is DescriptionApi.ConfigurationItem -> this.domainType
            else -> null
        }

    val instanceType: String?
        get() = (this as? ScriptApi.InstanceApi)?.instanceType

    val isModuleApi: Boolean
        get() = modulePath != null

    sealed interface DescriptionApi : ApiType {
        data class ConfigurationDomainEntry(override val domainType: XMakeConfigurationDomainType) : DescriptionApi {
            override val displayText = domainType.toKeyword()
        }

        data class ConfigurationDomainEnd(override val domainType: XMakeConfigurationDomainType) : DescriptionApi {
            override val displayText = domainType.toKeyword()
        }

        data object NamespaceEntry : DescriptionApi {
            override val displayText = "namespace"
        }

        data object NamespaceEnd : DescriptionApi {
            override val displayText = "namespace"
        }

        data object GlobalInterface : DescriptionApi {
            override val displayText = "global"
        }

        data class ConfigurationItem(override val domainType: XMakeConfigurationDomainType) : DescriptionApi {
            override val displayText = domainType.toKeyword()
        }

        data class BuiltinModuleApi(override val modulePath: String) : DescriptionApi {
            override val displayText = modulePath
        }
    }

    sealed interface ScriptApi : ApiType {
        data object TopLevelApi : ScriptApi {
            override val displayText = "script"
        }

        data class BuiltinModuleApi(override val modulePath: String) : ScriptApi {
            override val displayText = modulePath
        }

        data class ExtensionModuleApi(override val modulePath: String) : ScriptApi {
            override val displayText = modulePath
        }

        data class InstanceApi(override val instanceType: String) : ScriptApi {
            override val displayText = instanceType
        }
    }
}
