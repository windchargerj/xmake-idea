package io.xmake.lang.analysis.model

import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

/**
 * Validation error data class.
 */
data class ValidationError(
    val message: String,
    val availableScopes: List<String>,
    val severity: Severity = Severity.ERROR,
    val errorElement: XMakeLuaIdentifier? = null
)

enum class Severity {
    ERROR
}
