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
        resolveModuleFile(modulePath, rootDir, noLocal = false)

    fun resolveModuleFile(
        modulePath: String,
        rootDir: String?,
        noLocal: Boolean
    ): ImportModuleFileResolver.ResolvedModuleFile? =
        ImportModuleFileResolver.resolveModuleFile(
            scriptDirectory = scriptDirectory,
            modulePath = modulePath,
            rootDir = rootDir,
            noLocal = noLocal
        )

    fun extractLocalModuleExports(moduleFile: VirtualFile, modulePath: String): ModuleExports =
        extractLocalExports(
            moduleFile = moduleFile,
            modulePath = modulePath,
            psiExtractor = { psiFile -> LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(psiFile) },
            textExtractor = { text -> LocalModuleApiExtractor.extractTopLevelPublicFunctionDeclarations(project, text) },
            psiHasUnknownMembers = { psiFile -> LocalModuleApiExtractor.hasUnknownTopLevelPublicFunctionExports(psiFile) },
            textHasUnknownMembers = { text -> LocalModuleApiExtractor.hasUnknownTopLevelPublicFunctionExports(project, text) }
        )

    private fun extractLocalExports(
        moduleFile: VirtualFile,
        modulePath: String,
        psiExtractor: (XMakeLuaFile) -> List<ModuleExportDeclaration>,
        textExtractor: (String) -> List<ModuleExportDeclaration>,
        psiHasUnknownMembers: (XMakeLuaFile) -> Boolean,
        textHasUnknownMembers: (String) -> Boolean
    ): ModuleExports = ReadAction.compute<ModuleExports, RuntimeException> {
        val psiFile = PsiManager.getInstance(project).findFile(moduleFile) as? XMakeLuaFile
        val declarations = psiFile?.let(psiExtractor)
            ?: moduleFile.inputStream.use { input -> textExtractor(input.reader().readText()) }
        val hasUnknownMembers = psiFile?.let(psiHasUnknownMembers)
            ?: moduleFile.inputStream.use { input -> textHasUnknownMembers(input.reader().readText()) }
        val boundDeclarations = declarations.map { it.attachSourceFile(project, moduleFile) }

        ModuleExports(
            identifier = modulePath,
            apis = boundDeclarations.map { declaration -> createLocalApiModel(modulePath, declaration) },
            declarations = boundDeclarations,
            memberSurface = if (hasUnknownMembers) ModuleMemberSurface.UNKNOWN else ModuleMemberSurface.KNOWN
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
        val resolvedFile = context.resolveModuleFile(binding.modulePath, binding.rootDir, binding.noLocal) ?: return null
        if (resolvedFile.kind != ImportModuleFileResolver.ModuleFileKind.LUA_FILE) {
            return context.indexedExports(binding.modulePath)
                ?: ModuleExports(
                    identifier = binding.modulePath,
                    apis = emptyList(),
                    declarations = emptyList(),
                    kind = resolvedFile.kind.toImportedObjectKind(),
                    memberSurface = ModuleMemberSurface.UNKNOWN
                )
        }
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
            declarations = localExports.declarations,
            memberSurface = localExports.memberSurface
        )
    }
}

internal object InterfaceModuleExportsStrategy : ImportModuleExportsStrategy {
    override fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports? {
        if (binding.inherit) {
            return null
        }
        if (context.resolveModuleFile(binding.modulePath, binding.rootDir, binding.noLocal) != null ||
            context.indexedExports(binding.modulePath) != null
        ) {
            return null
        }
        val (parentModulePath, interfaceName) = binding.modulePath.splitByLastDot()
        val resolvedParentModulePath = parentModulePath ?: return null
        val resolvedParentFile = context.resolveModuleFile(
            resolvedParentModulePath,
            binding.rootDir,
            binding.noLocal
        ) ?: return null
        if (resolvedParentFile.kind != ImportModuleFileResolver.ModuleFileKind.LUA_FILE) {
            return null
        }
        val parentExports = context.extractLocalModuleExports(resolvedParentFile.file, resolvedParentModulePath)
        val indexedParentExports = context.indexedExports(resolvedParentModulePath)
        val hasExportedInterface = (parentExports.apis + indexedParentExports?.apis.orEmpty())
            .any { api -> api.name == interfaceName }
        if (!hasExportedInterface) {
            return null
        }
        return ModuleExports(
            identifier = binding.modulePath,
            apis = emptyList(),
            declarations = emptyList(),
            kind = ImportedObjectKind.CALLABLE
        )
    }
}

internal object IndexedModuleExportsStrategy : ImportModuleExportsStrategy {
    override fun resolve(binding: ImportBinding, context: ImportModuleExportsContext): ModuleExports? =
        context.indexedExports(binding.modulePath)
}

private fun ImportModuleFileResolver.ModuleFileKind.toImportedObjectKind(): ImportedObjectKind =
    when (this) {
        ImportModuleFileResolver.ModuleFileKind.LUA_FILE -> ImportedObjectKind.MODULE
        ImportModuleFileResolver.ModuleFileKind.LUA_DIRECTORY -> ImportedObjectKind.DIRECTORY
        ImportModuleFileResolver.ModuleFileKind.NATIVE_BINARY -> ImportedObjectKind.NATIVE_BINARY
        ImportModuleFileResolver.ModuleFileKind.NATIVE_SHARED -> ImportedObjectKind.NATIVE_SHARED
    }
