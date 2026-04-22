package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

class ImportPathLookup internal constructor(
    private val project: Project
) {

    private fun importCallAt(position: PsiElement?): LuaFunctionCall? =
        position
            ?.let { PsiTreeUtil.getParentOfType(it, LuaFunctionCall::class.java) }
            ?.takeIf(ImportCallParser::isImportCall)

    private fun resolveRootDirAt(position: PsiElement?): String? =
        importCallAt(position)?.let(ImportCallParser::resolveRootDir)

    private fun childModulesAt(
        anchor: PsiElement?,
        parentPath: String,
        rootDir: String? = resolveRootDirAt(anchor),
        extensionChildModules: Collection<String> = emptyList()
    ): List<String> {
        val normalizedParentPath = parentPath.trim().trimEnd('.')
        return ImportModuleFileResolver.childModules(
            scriptDirectory = ImportModuleFileResolver.scriptDirectoryOf(anchor),
            currentFileStem = ImportModuleFileResolver.currentFileStemOf(anchor),
            parentPath = normalizedParentPath,
            rootDir = rootDir,
            extensionChildModules = extensionChildModules
        )
    }

    fun childModules(
        parentPath: String,
        position: PsiElement? = null
    ): List<String> {
        val normalizedParentPath = parentPath.trim().trimEnd('.')
        return childModulesAt(
            anchor = position,
            parentPath = normalizedParentPath,
            extensionChildModules = project.xmakeApi.extensionChildModules(normalizedParentPath)
        )
    }
}
