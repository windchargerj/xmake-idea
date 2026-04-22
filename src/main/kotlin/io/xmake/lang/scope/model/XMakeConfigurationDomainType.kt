package io.xmake.lang.scope.model

import java.util.Locale

enum class XMakeConfigurationDomainType {
    TARGET,
    OPTION,
    RULE,
    TASK,
    TOOLCHAIN,
    PACKAGE;

    fun toKeyword(): String = name.lowercase(KEYWORD_LOCALE)

    fun toEndKeyword(): String = "${toKeyword()}_end"

    fun toDisplayName(): String = toKeyword()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(KEYWORD_LOCALE) else it.toString() }

    companion object {
        private val KEYWORD_LOCALE: Locale = Locale.ROOT

        private val byKeyword: Map<String, XMakeConfigurationDomainType> = entries.associateBy { it.toKeyword() }

        val keywords: List<String> = entries.map { it.toKeyword() }

        fun fromKeyword(keyword: String): XMakeConfigurationDomainType? {
            val lowercaseKeyword = keyword.lowercase(KEYWORD_LOCALE)
            return byKeyword[lowercaseKeyword]
        }

        fun fromEndKeyword(keyword: String): XMakeConfigurationDomainType? =
            keyword.lowercase(KEYWORD_LOCALE)
                .takeIf { it.endsWith("_end") }
                ?.removeSuffix("_end")
                ?.let(::fromKeyword)
    }
}
