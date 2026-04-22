package io.xmake.lang.declarations.resolution

import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.catalog.ApiIndex

/**
 * Resolves qualified API selectors against the indexed API catalog.
 */
class QualifiedApiResolver(
    private val lookup: ApiIndex
) {

    fun resolveQualifiedSelector(selector: QualifiedApiSelector, context: ApiLookupView): ApiResolutionResult {
        return when (selector) {
            is QualifiedApiSelector.ModuleFunction -> {
                val moduleApis = lookup.moduleFunctions(selector.modulePath, context)
                val api = moduleApis.find { it.name == selector.functionName }
                if (api != null) ApiResolutionResult.Resolved(ApiResolution(api = api))
                else ApiResolutionResult.NotFound
            }

            is QualifiedApiSelector.InstanceMethod -> {
                val apis = lookup.instanceApis(selector.instanceType, context)
                val api = apis.find { it.name == selector.methodName }
                if (api != null) ApiResolutionResult.Resolved(ApiResolution(api = api))
                else ApiResolutionResult.NotFound
            }
        }
    }
}
