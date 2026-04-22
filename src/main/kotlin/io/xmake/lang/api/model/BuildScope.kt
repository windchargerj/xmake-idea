package io.xmake.lang.api.model

import io.xmake.lang.psi.xmake.*

sealed interface BuildScope {

    data object Global : BuildScope

    data class Domain(
        val type: DomainScope.DomainType,
        val name: String? = null
    ) : BuildScope

    data object Script : BuildScope

    data object Namespace : BuildScope

    companion object {
        fun fromPsiScope(scope: PsiScope): BuildScope? = when (scope) {
            is GlobalScope -> Global
            is DomainScope -> Domain(scope.type)
            is ScriptScope -> Script
            is NamespaceScope -> Namespace
            else -> null
        }
    }
}

sealed interface ConditionExpr {
    data class IsHost(val os: String) : ConditionExpr

    data class HasConfig(val option: String) : ConditionExpr

    data class IsKind(val kind: String) : ConditionExpr

    data class And(val left: ConditionExpr, val right: ConditionExpr) : ConditionExpr

    data class Or(val left: ConditionExpr, val right: ConditionExpr) : ConditionExpr
}

@JvmInline
value class ScopeAvailability private constructor(private val flags: Int) {

    fun isAvailableIn(scope: BuildScope): Boolean {
        return when (scope) {
            is BuildScope.Global -> flags and GLOBAL_FLAG != 0
            is BuildScope.Domain -> flags and DOMAIN_FLAG != 0
            is BuildScope.Script -> flags and SCRIPT_FLAG != 0
            is BuildScope.Namespace -> flags and NAMESPACE_FLAG != 0
        }
    }

    val isGlobalAvailable: Boolean get() = flags and GLOBAL_FLAG != 0

    val isDomainAvailable: Boolean get() = flags and DOMAIN_FLAG != 0

    val isNamespaceAvailable: Boolean get() = flags and NAMESPACE_FLAG != 0

    val isScriptAvailable: Boolean get() = flags and SCRIPT_FLAG != 0

    override fun toString(): String = buildString {
        append("ScopeAvailability(")
        val scopes = mutableListOf<String>()
        if (isGlobalAvailable) scopes.add("GLOBAL")
        if (isDomainAvailable) scopes.add("DOMAIN")
        if (isNamespaceAvailable) scopes.add("NAMESPACE")
        if (isScriptAvailable) scopes.add("SCRIPT")
        append(scopes.joinToString(", "))
        append(")")
    }

    companion object {
        const val GLOBAL_FLAG = 1

        const val DOMAIN_FLAG = 2

        const val NAMESPACE_FLAG = 4

        const val SCRIPT_FLAG = 8

        val GLOBAL_ONLY = ScopeAvailability(GLOBAL_FLAG)
        val DOMAIN_ONLY = ScopeAvailability(DOMAIN_FLAG)
        val NAMESPACE_ONLY = ScopeAvailability(NAMESPACE_FLAG)
        val SCRIPT_ONLY = ScopeAvailability(SCRIPT_FLAG)
        val GLOBAL_AND_DOMAIN = ScopeAvailability(GLOBAL_FLAG or DOMAIN_FLAG)
        val GLOBAL_DOMAIN_AND_NAMESPACE = ScopeAvailability(GLOBAL_FLAG or DOMAIN_FLAG or NAMESPACE_FLAG)
        val ALL = ScopeAvailability(GLOBAL_FLAG or DOMAIN_FLAG or NAMESPACE_FLAG or SCRIPT_FLAG)

        fun of(
            global: Boolean = false,
            domain: Boolean = false,
            namespace: Boolean = false,
            script: Boolean = false
        ): ScopeAvailability = ScopeAvailability(
            (if (global) GLOBAL_FLAG else 0) or
            (if (domain) DOMAIN_FLAG else 0) or
            (if (namespace) NAMESPACE_FLAG else 0) or
            (if (script) SCRIPT_FLAG else 0)
        )
    }
}
