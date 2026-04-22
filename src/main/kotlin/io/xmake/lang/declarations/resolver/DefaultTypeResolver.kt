package io.xmake.lang.declarations.resolver

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.VerifiedMemberReturnTypes
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.syntax.psi.XMakeLuaFile

@Service(Service.Level.PROJECT)
class DefaultTypeResolver(private val project: Project) : TypeResolver {

    private val LOG = logger<DefaultTypeResolver>()

    private val api by lazy {
        try {
            XMakeApi.getInstance(project)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to load XMakeApi", e)
            null
        }
    }

    override fun resolveType(identifier: String, context: ApiLookupView): XMakeType? {
        if (isBuiltinModuleAvailable(identifier, context)) {
            return XMakeType.Module(identifier, context)
        }

        return null
    }

    override fun resolveMemberReturnType(receiverType: XMakeType, memberName: String, separator: String): XMakeType? {
        val instanceType = receiverType as? XMakeType.Instance ?: return null
        if (separator != ":") return null
        return VerifiedMemberReturnTypes.resolve(instanceType.typeName, memberName)
    }

    override fun isModulePath(path: String, context: ApiLookupView): Boolean {
        return isBuiltinModulePathAvailable(path, context)
    }

    override fun isModulePath(path: String, context: ApiLookupView, file: XMakeLuaFile?): Boolean =
        isModulePath(path, context, file, null)

    private fun isBuiltinModuleAvailable(identifier: String, context: ApiLookupView): Boolean {
        return api?.isBuiltinModule(identifier, context) ?: false
    }

    private fun isBuiltinModulePathAvailable(path: String, context: ApiLookupView): Boolean {
        return api?.isBuiltinModulePath(path, context) ?: false
    }

    override fun isModulePath(
        path: String,
        context: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement?
    ): Boolean {
        if (path.isBlank()) return false
        if (isBuiltinModulePathAvailable(path, context)) return true

        val xmakeApi = api
        if (file != null && xmakeApi != null) {
            xmakeApi.resolveVisibleModulePath(path, context, file, place)
                ?: return false
            return true
        }
        return false
    }
}
