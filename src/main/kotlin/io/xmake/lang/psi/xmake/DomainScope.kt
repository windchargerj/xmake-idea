package io.xmake.lang.psi.xmake

import com.intellij.lang.ASTNode
import io.xmake.lang.api.model.BuildScope
import java.util.*

class DomainScope(node: ASTNode, val type: DomainType) : DescriptionScope(node) {
    override val xmakeScope: BuildScope get() = BuildScope.Domain(type)

    enum class DomainType {
        TARGET,
        OPTION,
        RULE,
        TASK,
        TOOLCHAIN,
        PACKAGE,
        NAMESPACE;

        companion object {
            private val DEFAULT_LOCALE: Locale = Locale.getDefault()

            private val byText: Map<String, DomainType> = entries.associateBy { it.toText() }

            fun DomainType.toText(): String = name.lowercase(DEFAULT_LOCALE)

            fun DomainType.toPresentableText(): String = toText()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(DEFAULT_LOCALE) else it.toString() }

            val types: List<String> = entries.map { it.toText() }

            val openSymbols: List<String> = types

            val closingSymbols: List<String> = types.map { "${it}_end" }

            val symbols: List<String> = openSymbols + closingSymbols

            fun fromString(typeName: String): DomainType? {
                val lowercaseName = typeName.lowercase(DEFAULT_LOCALE)
                return byText[lowercaseName]
            }

            fun fromStringOrThrow(typeName: String): DomainType =
                fromString(typeName) ?: throw IllegalArgumentException("Unknown domain type: $typeName")
        }
    }

    companion object {
        fun create(node: ASTNode, typeName: String): DomainScope? {
            val type = DomainType.fromString(typeName) ?: return null
            return DomainScope(node, type)
        }
    }
}
