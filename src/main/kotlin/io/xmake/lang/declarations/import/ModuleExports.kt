package io.xmake.lang.declarations.import

import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.model.ApiModel

internal data class ModuleExports(
    val identifier: String,
    val identity: String = identifier,
    val apis: List<ApiModel>,
    val declarations: List<ModuleExportDeclaration> = emptyList(),
    val kind: ImportedObjectKind = ImportedObjectKind.MODULE,
    val memberSurface: ModuleMemberSurface = ModuleMemberSurface.KNOWN
) {
    val hasUnknownMembers: Boolean
        get() = memberSurface == ModuleMemberSurface.UNKNOWN
}

internal fun ModuleExports.toImportedModuleView(
    primaryReceiverName: String?,
    primaryBoundName: String?,
    boundNames: Set<String> = emptySet(),
    secondaryReceiverNames: Set<String> = emptySet(),
    hasReturnCaptureBinding: Boolean = false
): ImportedModuleView {
    return ImportedModuleView(
        modulePath = identifier,
        identity = identity,
        apis = apis,
        declarations = declarations,
        kind = kind,
        memberSurface = memberSurface,
        primaryReceiverName = primaryReceiverName,
        primaryBoundName = primaryBoundName,
        boundNames = boundNames,
        secondaryReceiverNames = secondaryReceiverNames,
        hasReturnCaptureBinding = hasReturnCaptureBinding
    )
}

internal fun ApiIndex.findIndexedModuleExports(modulePath: String): ModuleExports? {
    val apis = importableModuleApis(modulePath)
    if (apis.isEmpty() && !hasExtensionModuleOrChildren(modulePath)) {
        return null
    }
    return ModuleExports(identifier = modulePath, apis = apis)
}
