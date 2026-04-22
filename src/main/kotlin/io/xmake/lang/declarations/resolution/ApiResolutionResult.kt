package io.xmake.lang.declarations.resolution

/**
 * Result of resolving an identifier or qualified selector to an API entry.
 */
sealed class ApiResolutionResult {
    data class Resolved(val resolution: ApiResolution) : ApiResolutionResult()
    data object NotFound : ApiResolutionResult()
}
