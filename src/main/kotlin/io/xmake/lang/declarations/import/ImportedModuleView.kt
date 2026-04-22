package io.xmake.lang.declarations.import

import io.xmake.lang.declarations.model.ApiModel

data class ImportedModuleView(
    val modulePath: String,
    val apis: List<ApiModel>,
    val declarations: List<ModuleExportDeclaration> = emptyList(),
    val primaryReceiverName: String?,
    val primaryBoundName: String? = primaryReceiverName,
    val boundNames: Set<String> = setOfNotNull(primaryBoundName),
    val secondaryReceiverNames: Set<String> = emptySet()
) {
    // ImportedModuleView preserves both receiver-facing names used by
    // script/module-member access and the broader IDE-visible bound names.
    val identifier: String
        get() = modulePath

    val receiverNames: Set<String>
        get() = setOfNotNull(primaryReceiverName) + secondaryReceiverNames
}
