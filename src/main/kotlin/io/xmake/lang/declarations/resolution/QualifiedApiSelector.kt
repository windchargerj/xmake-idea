package io.xmake.lang.declarations.resolution

/**
 * Semantic selector for API access that already carries an explicit receiver,
 * such as `path.join` or `target:add`.
 */
sealed interface QualifiedApiSelector {
    data class ModuleFunction(
        val modulePath: String,
        val functionName: String
    ) : QualifiedApiSelector

    data class InstanceMethod(
        val instanceType: String,
        val methodName: String
    ) : QualifiedApiSelector
}
