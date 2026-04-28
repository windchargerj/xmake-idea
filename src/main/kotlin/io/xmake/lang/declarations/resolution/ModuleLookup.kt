package io.xmake.lang.declarations.resolution

import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.ModulePath
import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.import.ImportLookup
import io.xmake.lang.declarations.import.ImportedModuleView
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile

internal class ModuleLookup(
    private val lookup: ApiIndex,
    private val imports: ImportLookup
) {
    private data class VisibleModuleResolution(
        val visiblePath: String,
        val importedModule: ImportedModuleView?
    )

    fun resolveVisibleModulePath(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): String? = resolveVisibleModule(modulePath, context, file, place)?.visiblePath

    fun visibleModuleFunctions(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): List<ApiModel> {
        val resolution = resolveVisibleModule(modulePath, context, file, place)
            ?: return findImportedModuleData(modulePath, context, file, place)?.apis.orEmpty()
        resolution.importedModule?.let { importedModule ->
            if (importedModule.identifier == resolution.visiblePath) {
                if (!importedModule.isModuleLike) {
                    return emptyList()
                }
                return importedModule.apis
            }
        }
        return lookup.moduleFunctions(resolution.visiblePath, context)
    }

    fun visibleChildModulesForReceiver(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): List<String> {
        val resolution = resolveVisibleModule(modulePath, context, file, place)
            ?: return findImportedModuleData(modulePath, context, file, place)
                ?.let { lookup.visibleChildModules(modulePath, context) }
                .orEmpty()
        resolution.importedModule?.let { importedModule ->
            if (importedModule.identifier == resolution.visiblePath) {
                return emptyList()
            }
        }
        return lookup.visibleChildModules(resolution.visiblePath, context)
    }

    fun isVisibleModule(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): Boolean {
        val resolution = resolveVisibleModule(modulePath, context, file, place) ?: return false
        return isVisibleModule(resolution, context)
    }

    fun isVisibleModulePath(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): Boolean {
        val resolution = resolveVisibleModule(modulePath, context, file, place) ?: return false
        return isVisibleModule(resolution, context) ||
            lookup.visibleChildModules(resolution.visiblePath, context).isNotEmpty()
    }

    private fun isVisibleModule(
        resolution: VisibleModuleResolution,
        context: ApiLookupView
    ): Boolean =
        lookup.isBuiltinModulePath(resolution.visiblePath, context) ||
            (context.domain is XMakeDomain.Script && lookup.isExtensionModulePath(resolution.visiblePath)) ||
            (context.domain is XMakeDomain.Script &&
                resolution.importedModule?.identifier == resolution.visiblePath &&
                resolution.importedModule?.isModuleLike == true)

    private fun resolveVisibleModule(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement?
    ): VisibleModuleResolution? {
        if (modulePath.isBlank()) {
            return null
        }

        if (lookup.isBuiltinModulePath(modulePath, context)) {
            return VisibleModuleResolution(
                visiblePath = modulePath,
                importedModule = null
            )
        }

        if (context.domain !is XMakeDomain.Script || file == null) {
            return null
        }

        val importView = imports.viewAt(file, place)
        val pathSegments = ModulePath.parse(modulePath)
        val firstSegment = pathSegments.firstOrNull() ?: return null
        val importedModule = importView.resolveReceiverModule(firstSegment) ?: return null
        if (!importedModule.isModuleLike) {
            return null
        }

        val visiblePath = (listOf(importedModule.identifier) + pathSegments.drop(1)).joinToString(".")
        return VisibleModuleResolution(
            visiblePath = visiblePath,
            importedModule = importedModule
        )
    }

    private fun findImportedModuleData(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement?
    ): ImportedModuleView? {
        if (modulePath.isBlank() || context.domain !is XMakeDomain.Script || file == null) {
            return null
        }
        return imports.viewAt(file, place).importedModules
            .asReversed()
            .firstOrNull { importedModule ->
                importedModule.identifier == modulePath &&
                    importedModule.isModuleLike &&
                    importedModule.hasReturnCaptureBinding
            }
    }
}
