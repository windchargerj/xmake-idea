package io.xmake.lang.scope.model

sealed interface XMakeDomain {

    data object Description : XMakeDomain

    data class Configuration(val type: XMakeConfigurationDomainType) : XMakeDomain

    data object Script : XMakeDomain
}

val XMakeDomain.configurationDomainType: XMakeConfigurationDomainType?
    get() = (this as? XMakeDomain.Configuration)?.type

@JvmInline
value class ApiAvailability private constructor(private val flags: Int) {

    fun isAvailableIn(domain: XMakeDomain): Boolean =
        when (domain) {
            is XMakeDomain.Description -> flags and DESCRIPTION_FLAG != 0
            is XMakeDomain.Configuration -> flags and CONFIGURATION_FLAG != 0
            is XMakeDomain.Script -> flags and SCRIPT_FLAG != 0
        }

    val isDescriptionAvailable: Boolean get() = flags and DESCRIPTION_FLAG != 0

    val isConfigurationAvailable: Boolean get() = flags and CONFIGURATION_FLAG != 0

    val isScriptAvailable: Boolean get() = flags and SCRIPT_FLAG != 0

    override fun toString(): String = "ApiAvailability(${availableDomains.joinToString(", ")})"

    private val availableDomains: List<String>
        get() = buildList {
            if (isDescriptionAvailable) add("DESCRIPTION")
            if (isConfigurationAvailable) add("CONFIGURATION")
            if (isScriptAvailable) add("SCRIPT")
        }

    companion object {
        const val DESCRIPTION_FLAG = 1

        const val CONFIGURATION_FLAG = 2

        const val SCRIPT_FLAG = 4

        val DESCRIPTION_ONLY = ApiAvailability(DESCRIPTION_FLAG)
        val CONFIGURATION_ONLY = ApiAvailability(CONFIGURATION_FLAG)
        val SCRIPT_ONLY = ApiAvailability(SCRIPT_FLAG)
        val DESCRIPTION_AND_CONFIGURATION = ApiAvailability(DESCRIPTION_FLAG or CONFIGURATION_FLAG)
        val ALL = ApiAvailability(DESCRIPTION_FLAG or CONFIGURATION_FLAG or SCRIPT_FLAG)

        fun of(
            description: Boolean = false,
            configuration: Boolean = false,
            script: Boolean = false
        ): ApiAvailability = ApiAvailability(
            (if (description) DESCRIPTION_FLAG else 0) or
                (if (configuration) CONFIGURATION_FLAG else 0) or
                (if (script) SCRIPT_FLAG else 0)
        )
    }
}
