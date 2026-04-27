package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement

internal enum class ImportBindingKind {
    MODULE_IMPORT,
    ADD_IMPORTS,
    RETURN_CAPTURE
}

enum class ImportBindingOrigin {
    IMPORT,
    ADD_IMPORTS
}

internal data class ImportBinding(
    val modulePath: String,
    val receiverNames: Set<String> = emptySet(),
    val primaryReceiverName: String? = null,
    val boundNames: Set<String> = emptySet(),
    val primaryBoundName: String? = null,
    val inherit: Boolean = false,
    val rootDir: String? = null,
    val noLocal: Boolean = false,
    val declarationElement: PsiElement? = null,
    val origin: ImportBindingOrigin = ImportBindingOrigin.IMPORT,
    val kind: ImportBindingKind = ImportBindingKind.MODULE_IMPORT
)

private val ImportSpec.defaultBoundName: String
    get() = alias ?: modulePath.substringAfterLast('.')

private val ImportSpec.primaryReceiverBindingName: String?
    get() = when {
        anonymous -> null
        inherit -> null
        else -> defaultBoundName
    }

private val ImportSpec.primaryBoundName: String?
    get() = when {
        anonymous -> null
        inherit -> null
        else -> defaultBoundName
    }

private val ImportSpec.receiverBindingNames: Set<String>
    get() = setOfNotNull(primaryReceiverBindingName)

private val ImportSpec.boundNames: Set<String>
    get() = setOfNotNull(primaryBoundName)

internal fun ImportDeclaration.toImportBindings(): List<ImportBinding> = when (this) {
    is ModuleImportDeclaration -> toImportBindings()
    is AddImportDeclaration -> listOf(toImportBinding())
}

private fun ModuleImportDeclaration.toImportBindings(): List<ImportBinding> = buildList {
    add(
        ImportBinding(
            modulePath = spec.modulePath,
            receiverNames = spec.receiverBindingNames,
            primaryReceiverName = spec.primaryReceiverBindingName,
            boundNames = spec.boundNames,
            primaryBoundName = spec.primaryBoundName,
            inherit = spec.inherit,
            rootDir = spec.rootDir,
            noLocal = spec.noLocal,
            declarationElement = declarationElement,
            kind = ImportBindingKind.MODULE_IMPORT
        )
    )
    variableAlias?.let { alias ->
        add(
            ImportBinding(
                modulePath = spec.modulePath,
                receiverNames = setOf(alias),
                primaryReceiverName = alias,
                boundNames = setOf(alias),
                primaryBoundName = alias,
                rootDir = spec.rootDir,
                noLocal = spec.noLocal,
                declarationElement = declarationElement,
                kind = ImportBindingKind.RETURN_CAPTURE
            )
        )
    }
}

private fun AddImportDeclaration.toImportBinding(): ImportBinding {
    val bindingName = modulePath.substringAfterLast('.')
    return ImportBinding(
        modulePath = modulePath,
        receiverNames = setOf(bindingName),
        primaryReceiverName = bindingName,
        boundNames = setOf(bindingName),
        primaryBoundName = bindingName,
        declarationElement = declarationElement,
        origin = ImportBindingOrigin.ADD_IMPORTS,
        kind = ImportBindingKind.ADD_IMPORTS
    )
}
