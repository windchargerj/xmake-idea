package io.xmake.lang.scope.model

object XMakeDescriptionDomainRules {

    const val NAMESPACE_ENTRY_KEYWORD: String = "namespace"

    const val NAMESPACE_END_KEYWORD: String = "namespace_end"

    val configurationDomainTypes: List<XMakeConfigurationDomainType> = XMakeConfigurationDomainType.entries.toList()

    val configurationDomainEntryKeywords: Set<String> =
        configurationDomainTypes.mapTo(linkedSetOf()) { it.toKeyword() }

    val configurationDomainEndKeywords: Set<String> =
        configurationDomainTypes.mapTo(linkedSetOf()) { it.toEndKeyword() }

    val structuralEntryKeywords: Set<String> = configurationDomainEntryKeywords + NAMESPACE_ENTRY_KEYWORD

    val structuralEndKeywords: Set<String> = configurationDomainEndKeywords + NAMESPACE_END_KEYWORD

    val structuralKeywords: Set<String> = structuralEntryKeywords + structuralEndKeywords

    fun isConfigurationDomainEntry(functionName: String): Boolean =
        functionName in configurationDomainEntryKeywords

    fun isConfigurationDomainEnd(functionName: String): Boolean =
        functionName in configurationDomainEndKeywords

    fun isStructuralEntry(functionName: String): Boolean =
        functionName in structuralEntryKeywords

    fun isStructuralEnd(functionName: String): Boolean =
        functionName in structuralEndKeywords

    fun isStructuralKeyword(functionName: String): Boolean =
        functionName in structuralKeywords

    fun isNamespaceEntry(functionName: String): Boolean =
        functionName == NAMESPACE_ENTRY_KEYWORD

    fun isNamespaceEnd(functionName: String): Boolean =
        functionName == NAMESPACE_END_KEYWORD

    fun configurationDomainTypeForEntry(functionName: String): XMakeConfigurationDomainType? =
        XMakeConfigurationDomainType.fromKeyword(functionName)

    fun configurationDomainTypeForEnd(functionName: String): XMakeConfigurationDomainType? =
        XMakeConfigurationDomainType.fromEndKeyword(functionName)

    fun matchesConfigurationDomainEnd(type: XMakeConfigurationDomainType, functionName: String): Boolean =
        configurationDomainTypeForEnd(functionName) == type

    fun structuralEntryAvailability(@Suppress("UNUSED_PARAMETER") functionName: String): ApiAvailability =
        ApiAvailability.DESCRIPTION_ONLY

    fun structuralEndAvailability(functionName: String): ApiAvailability =
        if (isNamespaceEnd(functionName)) ApiAvailability.DESCRIPTION_ONLY
        else ApiAvailability.CONFIGURATION_ONLY

    fun configurationItemAvailability(domainType: XMakeConfigurationDomainType): ApiAvailability =
        ApiAvailability.CONFIGURATION_ONLY

    fun globalInterfaceAvailability(@Suppress("UNUSED_PARAMETER") functionName: String): ApiAvailability =
        ApiAvailability.DESCRIPTION_ONLY

    fun isGlobalInterfaceVisibleInConfigurationDomain(
        functionName: String,
        @Suppress("UNUSED_PARAMETER") domainType: XMakeConfigurationDomainType
    ): Boolean = functionName !in ROOT_ONLY_GLOBAL_INTERFACES

    fun isBuiltinFunctionInterface(functionName: String): Boolean =
        functionName in BUILTIN_FUNCTION_INTERFACES

    private val BUILTIN_FUNCTION_INTERFACES: Set<String> = setOf(
        "format",
        "get_config",
        "getenv",
        "has_config",
        "has_package",
        "ipairs",
        "is_arch",
        "is_config",
        "is_cross",
        "is_host",
        "is_kind",
        "is_mode",
        "is_os",
        "is_plat",
        "is_subhost",
        "pairs",
        "print",
        "printf",
        "tonumber",
        "tostring",
        "type",
        "unpack"
    )

    private val ROOT_ONLY_GLOBAL_INTERFACES: Set<String> = setOf(
        "add_packagedirs",
        "add_repositories",
        "add_requireconfs",
        "add_requires",
        "set_allowedarchs",
        "set_allowedmodes",
        "set_allowedplats",
        "set_config",
        "set_defaultarchs",
        "set_defaultmode",
        "set_defaultplat",
        "set_description",
        "set_project"
    )
}
