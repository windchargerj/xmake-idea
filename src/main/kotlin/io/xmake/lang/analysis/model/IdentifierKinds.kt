package io.xmake.lang.analysis.model

internal sealed interface IdentifierHighlightKind {
    companion object {
        val entries: Set<IdentifierHighlightKind> =
            linkedSetOf<IdentifierHighlightKind>().apply {
                addAll(IdentifierSemanticKind.entries)
                addAll(IdentifierStructuralKind.entries)
            }
    }
}

internal enum class IdentifierSemanticKind(
    val isReferenceCandidate: Boolean = true
) : IdentifierHighlightKind {
    FUNCTION_DECLARATION,
    FUNCTION_CALL,
    DESCRIPTION_BUILTIN_FUNCTION_CALL,
    SCRIPT_BUILTIN_FUNCTION_CALL,
    DESCRIPTION_API_CALL,
    SCRIPT_API_CALL,
    MODULE_CALL,
    INSTANCE_METHOD,
    TABLE_KEY(isReferenceCandidate = false),
    TABLE_FIELD(isReferenceCandidate = false),
    LOCAL_VARIABLE(isReferenceCandidate = false),
    PARAMETER(isReferenceCandidate = false),
    LABEL(isReferenceCandidate = false),
}

internal enum class IdentifierStructuralKind : IdentifierHighlightKind {
    CONFIGURATION_DOMAIN_ENTRY,
    CONFIGURATION_DOMAIN_END,
    NAMESPACE_ENTRY,
    NAMESPACE_END,
}

internal enum class IdentifierResolutionStatus {
    RESOLVED,
    UNRESOLVED_VARIABLE,
    UNRESOLVED_FUNCTION;

    val isUnresolved: Boolean
        get() = this != RESOLVED
}
