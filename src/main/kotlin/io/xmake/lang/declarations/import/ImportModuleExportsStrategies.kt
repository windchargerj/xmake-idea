package io.xmake.lang.declarations.import

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.xmake.lang.declarations.splitByLastDot
import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.scope.model.ApiAvailability
import io.xmake.lang.syntax.psi.XMakeLuaFile

internal fun interface ImportModuleExportsStrategy {
    fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports?
}

internal class ImportModuleExportsContext(
    private val project: Project,
    private val lookup: ApiIndex,
    private val scriptDirectory: VirtualFile?
) {
    fun indexedExports(modulePath: String): ModuleExports? =
        lookup.findIndexedModuleExports(modulePath)

    fun resolveModuleFile(modulePath: String, rootDir: String?): ImportModuleFileResolver.ResolvedModuleFile? =
        ImportModuleFileResolver.resolveModuleFile(
            scriptDirectory = scriptDirectory,
            modulePath = modulePath,
            rootDir = rootDir
        )

    fun extractLocalModuleExports(moduleFile: VirtualFile, modulePath: String): ModuleExports =
        extractLocalExports(
            moduleFile = moduleFile,
            modulePath = modulePath,
            psiExtractor = { psiFile -> LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(psiFile) },
            textExtractor = { text -> LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(project, text) }
        )

    fun extractLocalInterfaceExports(
        moduleFile: VirtualFile,
        modulePath: String,
        interfaceName: String
    ): ModuleExports =
        extractLocalExports(
            moduleFile = moduleFile,
            modulePath = modulePath,
            psiExtractor = { psiFile ->
                LocalModuleApiExtractor.extractPublicInterfaceFunctionDeclarations(psiFile, interfaceName)
            },
            textExtractor = { text ->
                LocalModuleApiExtractor.extractPublicInterfaceFunctionDeclarations(project, text, interfaceName)
            }
        )

    private fun extractLocalExports(
        moduleFile: VirtualFile,
        modulePath: String,
        psiExtractor: (XMakeLuaFile) -> List<ModuleExportDeclaration>,
        textExtractor: (String) -> List<ModuleExportDeclaration>
    ): ModuleExports = ReadAction.compute<ModuleExports, RuntimeException> {
        val psiFile = PsiManager.getInstance(project).findFile(moduleFile) as? XMakeLuaFile
        val declarations = psiFile?.let(psiExtractor)
            ?: moduleFile.inputStream.use { input -> textExtractor(input.reader().readText()) }
        val boundDeclarations = declarations.map { it.attachSourceFile(project, moduleFile) }

        ModuleExports(
            identifier = modulePath,
            apis = boundDeclarations.map { declaration -> createLocalApiModel(modulePath, declaration) },
            declarations = boundDeclarations
        )
    }

    private fun createLocalApiModel(modulePath: String, declaration: ModuleExportDeclaration): ApiModel =
        ApiModel(
            fullName = "$modulePath.${declaration.name}",
            name = declaration.name,
            type = ApiType.ScriptApi.ExtensionModuleApi(modulePath),
            availability = ApiAvailability.SCRIPT_ONLY
        )
}

internal object ResolvedModuleFileExportsStrategy : ImportModuleExportsStrategy {
    override fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports? {
        val resolvedFile = context.resolveModuleFile(binding.modulePath, binding.rootDir) ?: return null
        val localExports = context.extractLocalModuleExports(resolvedFile.file, binding.modulePath)
        if (resolvedFile.source == ImportModuleFileResolver.ModuleFileSource.LOCAL) {
            return localExports
        }
        if (localExports.apis.isEmpty()) {
            return null
        }
        return ModuleExports(
            identifier = binding.modulePath,
            apis = (context.indexedExports(binding.modulePath)?.apis.orEmpty() + localExports.apis)
                .distinctBy { it.fullName },
            declarations = localExports.declarations
        )
    }
}

internal object InterfaceModuleExportsStrategy : ImportModuleExportsStrategy {
    override fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports? {
        val (parentModulePath, interfaceName) = binding.modulePath.splitByLastDot()
        val resolvedParentModulePath = parentModulePath ?: return null
        val parentFile = context.resolveModuleFile(resolvedParentModulePath, binding.rootDir)?.file ?: return null
        val exports = context.extractLocalInterfaceExports(parentFile, binding.modulePath, interfaceName)
        if (exports.apis.isEmpty()) {
            return null
        }
        return exports
    }
}

internal object IndexedModuleExportsStrategy : ImportModuleExportsStrategy {
    override fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports? =
        context.indexedExports(binding.modulePath)
}
