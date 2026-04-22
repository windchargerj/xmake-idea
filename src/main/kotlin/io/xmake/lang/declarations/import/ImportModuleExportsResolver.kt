package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.xmake.lang.declarations.catalog.ApiIndex

internal class ImportModuleExportsResolver(
    private val project: Project,
    private val lookup: ApiIndex,
    private val scriptDirectory: VirtualFile?
) {
    private val context = ImportModuleExportsContext(
        project = project,
        lookup = lookup,
        scriptDirectory = scriptDirectory
    )

    private val strategies: List<ImportModuleExportsStrategy> = listOf(
        ResolvedModuleFileExportsStrategy,
        InterfaceModuleExportsStrategy,
        IndexedModuleExportsStrategy
    )

    fun resolve(binding: ImportBinding): ModuleExports? =
        strategies.firstNotNullOfOrNull { strategy -> strategy.resolve(binding, context) }
}
