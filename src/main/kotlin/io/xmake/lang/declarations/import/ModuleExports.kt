package io.xmake.lang.declarations.import

import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.model.ApiModel

internal data class ModuleExports(
    val identifier: String,
    val apis: List<ApiModel>,
    val declarations: List<ModuleExportDeclaration> = emptyList()
)

internal fun ModuleExports.toImportedModuleView(
    primaryReceiverName: String?,
    primaryBoundName: String?,
    boundNames: Set<String> = emptySet(),
    secondaryReceiverNames: Set<String> = emptySet()
): ImportedModuleView {
    return ImportedModuleView(
        modulePath = identifier,
        apis = apis,
        declarations = declarations,
        primaryReceiverName = primaryReceiverName,
        primaryBoundName = primaryBoundName,
        boundNames = boundNames,
        secondaryReceiverNames = secondaryReceiverNames
    )
}

internal fun ApiIndex.findIndexedModuleExports(modulePath: String): ModuleExports? {
    val apis = importableModuleApis(modulePath)
    if (apis.isEmpty() && !hasExtensionModuleOrChildren(modulePath)) {
        return null
    }
    return ModuleExports(modulePath, apis)
}
