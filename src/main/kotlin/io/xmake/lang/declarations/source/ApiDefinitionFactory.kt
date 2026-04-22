package io.xmake.lang.declarations.source

import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.declarations.splitByColon
import io.xmake.lang.declarations.splitByDot
import io.xmake.lang.declarations.splitByLastDot
import io.xmake.lang.scope.model.ApiAvailability
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.utils.info.XMakeApis

object ApiDefinitionFactory {

    fun create(apis: XMakeApis): List<ApiModel> = buildList {
        addDescriptionStructureApis()
        addDescriptionApis(apis)
        addScriptApis(apis)
    }

    enum class ModuleType {
        DESCRIPTION_BUILTIN,
        SCRIPT_BUILTIN,
        EXTENSION,
        NONE
    }

    fun classifyModule(api: ApiModel): ModuleType {
        val modulePath = api.modulePath ?: return ModuleType.NONE
        return when {
            modulePath.startsWith("core.") -> ModuleType.EXTENSION
            api.type is ApiType.DescriptionApi.BuiltinModuleApi -> ModuleType.DESCRIPTION_BUILTIN
            api.type is ApiType.ScriptApi.BuiltinModuleApi -> ModuleType.SCRIPT_BUILTIN
            else -> ModuleType.NONE
        }
    }

    fun isExtensionModule(api: ApiModel): Boolean =
        api.modulePath?.startsWith("core.") == true

    fun isBuiltinModule(api: ApiModel): Boolean =
        api.type is ApiType.DescriptionApi.BuiltinModuleApi ||
            (api.type is ApiType.ScriptApi.BuiltinModuleApi && !isExtensionModule(api)) ||
            api.type is ApiType.ScriptApi.ExtensionModuleApi

    private fun MutableList<ApiModel>.addDescriptionStructureApis() {
        XMakeDescriptionDomainRules.configurationDomainTypes.forEach { domainType ->
            val name = domainType.toKeyword()
            add(ApiModel(
                fullName = name,
                name = name,
                type = ApiType.DescriptionApi.ConfigurationDomainEntry(domainType),
                availability = XMakeDescriptionDomainRules.structuralEntryAvailability(name)
            ))
        }

        add(ApiModel(
            fullName = XMakeDescriptionDomainRules.NAMESPACE_ENTRY_KEYWORD,
            name = XMakeDescriptionDomainRules.NAMESPACE_ENTRY_KEYWORD,
            type = ApiType.DescriptionApi.NamespaceEntry,
            availability = XMakeDescriptionDomainRules.structuralEntryAvailability(XMakeDescriptionDomainRules.NAMESPACE_ENTRY_KEYWORD)
        ))

        XMakeDescriptionDomainRules.configurationDomainTypes.forEach { domainType ->
            val name = domainType.toEndKeyword()
            add(ApiModel(
                fullName = name,
                name = name,
                type = ApiType.DescriptionApi.ConfigurationDomainEnd(domainType),
                availability = XMakeDescriptionDomainRules.structuralEndAvailability(name)
            ))
        }

        add(ApiModel(
            fullName = XMakeDescriptionDomainRules.NAMESPACE_END_KEYWORD,
            name = XMakeDescriptionDomainRules.NAMESPACE_END_KEYWORD,
            type = ApiType.DescriptionApi.NamespaceEnd,
            availability = XMakeDescriptionDomainRules.structuralEndAvailability(XMakeDescriptionDomainRules.NAMESPACE_END_KEYWORD)
        ))
    }

    private fun MutableList<ApiModel>.addDescriptionApis(apis: XMakeApis) {
        apis.descriptionBuiltinApis.distinct().forEach { name ->
            add(ApiModel(
                fullName = name,
                name = name,
                type = ApiType.DescriptionApi.GlobalInterface,
                availability = XMakeDescriptionDomainRules.globalInterfaceAvailability(name)
            ))
        }

        apis.descriptionScopeApis
            .distinct()
            .forEach { fullName ->
                val (scopeName, name) = fullName.splitByDot()
                val scopeKeyword = scopeName ?: fullName
                val domainType = requireNotNull(XMakeConfigurationDomainType.fromKeyword(scopeKeyword)) {
                    "Unexpected configuration-domain API '$fullName'"
                }
                add(ApiModel(
                    fullName = fullName,
                    name = name,
                    type = ApiType.DescriptionApi.ConfigurationItem(domainType),
                    availability = XMakeDescriptionDomainRules.configurationItemAvailability(domainType)
                ))
            }

        apis.descriptionBuiltinModuleApis.distinct().forEach { fullName ->
            val (modulePath, name) = fullName.splitByDot()
            add(ApiModel(
                fullName = fullName,
                name = name,
                type = ApiType.DescriptionApi.BuiltinModuleApi(modulePath ?: fullName),
                availability = ApiAvailability.DESCRIPTION_AND_CONFIGURATION
            ))
        }
    }

    private fun MutableList<ApiModel>.addScriptApis(apis: XMakeApis) {
        apis.scriptBuiltinApis.distinct().forEach { name ->
            add(ApiModel(
                fullName = name,
                name = name,
                type = ApiType.ScriptApi.TopLevelApi,
                availability = ApiAvailability.SCRIPT_ONLY
            ))
        }

        apis.scriptBuiltinModuleApis.distinct().forEach { fullName ->
            val (modulePath, name) = fullName.splitByDot()
            add(ApiModel(
                fullName = fullName,
                name = name,
                type = ApiType.ScriptApi.BuiltinModuleApi(modulePath ?: fullName),
                availability = ApiAvailability.SCRIPT_ONLY
            ))
        }

        apis.scriptExtensionModuleApis.distinct().forEach { fullName ->
            val (modulePath, name) = fullName.splitByLastDot()
            add(ApiModel(
                fullName = fullName,
                name = name,
                type = ApiType.ScriptApi.ExtensionModuleApi(modulePath ?: fullName),
                availability = ApiAvailability.SCRIPT_ONLY
            ))
        }

        apis.scriptInstanceApis.distinct().forEach { fullName ->
            val (instanceType, name) = fullName.splitByColon()
            add(ApiModel(
                fullName = fullName,
                name = name,
                type = ApiType.ScriptApi.InstanceApi(instanceType ?: fullName),
                availability = ApiAvailability.SCRIPT_ONLY
            ))
        }
    }
}
