package io.xmake.lang.declarations.import

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.xmake.lang.syntax.psi.XMakeLuaFile

class ImportLookup internal constructor(
    private val project: Project
) {

    private fun cacheFor(file: XMakeLuaFile): ImportLookupCache =
        ImportLookupCache.forFile(project, file)

    fun viewAt(
        file: XMakeLuaFile,
        place: PsiElement? = null
    ): ImportLookupView =
        ReadAction.compute<ImportLookupView, RuntimeException> {
            cacheFor(file).viewAt(place)
        }

    fun listInheritedModules(
        file: XMakeLuaFile,
        place: PsiElement? = null
    ): List<ImportedModuleView> =
        viewAt(file, place).inheritedModules

    fun findReceiverModule(
        file: XMakeLuaFile,
        receiverName: String,
        place: PsiElement? = null
    ): ImportedModuleView? =
        viewAt(file, place).resolveReceiverModule(receiverName)

    fun findReceiverBindingTargets(
        file: XMakeLuaFile,
        receiverName: String,
        place: PsiElement? = null
    ): ImportBindingTargets? =
        viewAt(file, place).resolveReceiverBindingTargets(receiverName)

    fun findInheritedApiExposures(
        file: XMakeLuaFile,
        name: String,
        place: PsiElement? = null
    ): InheritedApiExposures? =
        viewAt(file, place).resolveInheritedApiExposures(name)
}
