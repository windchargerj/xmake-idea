package io.xmake.lang.declarations.import

/**
 * Ordered inherited API candidates for a visible unqualified API name.
 *
 * `primary` preserves the current compatibility behavior where the latest inherited exposure wins.
 * `shadowed` keeps older exposures that point at the same API as `primary`.
 * `conflicts` keeps older exposures for the same name that point at different APIs.
 */
data class InheritedApiExposures(
    val primary: InheritedApiExposure,
    val shadowed: List<InheritedApiExposure> = emptyList(),
    val conflicts: List<InheritedApiExposure> = emptyList()
)
