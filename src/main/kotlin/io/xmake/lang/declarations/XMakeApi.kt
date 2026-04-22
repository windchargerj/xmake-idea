package io.xmake.lang.declarations

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.import.ImportPathLookup
import io.xmake.lang.declarations.import.ImportLookup
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.resolution.ApiResolutionResult
import io.xmake.lang.declarations.resolution.QualifiedApiResolver
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.declarations.resolution.UnqualifiedApiLookup
import io.xmake.lang.declarations.resolution.ModuleLookup
import io.xmake.lang.declarations.resolver.DefaultTypeResolver
import io.xmake.lang.declarations.resolver.TypeResolver
import io.xmake.lang.syntax.psi.XMakeLuaFile

@Service(Service.Level.PROJECT)
/**
 * Stable declarations boundary for consumers outside the declarations package.
 *
 * This service intentionally exposes only the composed entry points that
 * code insight, navigation, and analysis should rely on.
 */
class XMakeApi(val project: Project) {

    val lookup: ApiIndex by lazy { ApiIndex.create(project) }

    val imports: ImportLookup by lazy { ImportLookup(project) }

    private val importPathLookup: ImportPathLookup by lazy { ImportPathLookup(project) }

    private val qualifiedApiResolver: QualifiedApiResolver by lazy { QualifiedApiResolver(lookup) }

    private val unqualifiedApiLookup: UnqualifiedApiLookup by lazy { UnqualifiedApiLookup(lookup, imports) }

    private val moduleLookup: ModuleLookup by lazy { ModuleLookup(lookup, imports) }

    val typeResolver: TypeResolver
        get() = project.service<DefaultTypeResolver>()

    fun resolveQualifiedSelector(selector: QualifiedApiSelector, context: ApiLookupView): ApiResolutionResult =
        qualifiedApiResolver.resolveQualifiedSelector(selector, context)

    fun findUnqualifiedApi(
        name: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): ApiModel? =
        unqualifiedApiLookup.findUnqualifiedApi(name, context, file, place)

    fun findApisByName(name: String): List<ApiModel> =
        lookup.findByName(name)

    fun findApiByFullName(fullName: String): ApiModel? =
        lookup.findByFullName(fullName)

    fun visibleBuiltinModulePaths(context: ApiLookupView): Set<String> =
        lookup.visibleBuiltinModulePaths(context)

    fun isBuiltinModule(identifier: String, context: ApiLookupView): Boolean =
        lookup.isBuiltinModule(identifier, context)

    fun isBuiltinModulePath(modulePath: String, context: ApiLookupView): Boolean =
        lookup.isBuiltinModulePath(modulePath, context)

    fun availableTopLevelCallables(context: ApiLookupView): List<ApiModel> =
        lookup.availableTopLevelCallables(context)

    fun configurationItems(domainType: io.xmake.lang.scope.model.XMakeConfigurationDomainType): List<ApiModel> =
        lookup.configurationItems(domainType)

    fun instanceApis(instanceType: String, context: ApiLookupView): List<ApiModel> =
        lookup.instanceApis(instanceType, context)

    fun extensionChildModules(modulePath: String): List<String> =
        lookup.extensionChildModules(modulePath)

    fun isExtensionModulePath(modulePath: String): Boolean =
        lookup.isExtensionModulePath(modulePath)

    fun indexedModuleFunctions(modulePath: String, context: ApiLookupView): List<ApiModel> =
        lookup.moduleFunctions(modulePath, context)

    fun resolveVisibleModulePath(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): String? =
        moduleLookup.resolveVisibleModulePath(modulePath, context, file, place)

    fun visibleModuleFunctions(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): List<ApiModel> =
        moduleLookup.visibleModuleFunctions(modulePath, context, file, place)

    fun visibleChildModulesForReceiver(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): List<String> =
        moduleLookup.visibleChildModulesForReceiver(modulePath, context, file, place)

    fun isVisibleModule(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): Boolean =
        moduleLookup.isVisibleModule(modulePath, context, file, place)

    fun isVisibleModulePath(
        modulePath: String,
        context: ApiLookupView,
        file: XMakeLuaFile? = null,
        place: PsiElement? = null
    ): Boolean =
        moduleLookup.isVisibleModulePath(modulePath, context, file, place)

    fun importChildModules(
        prefix: String,
        position: PsiElement? = null
    ): List<String> =
        importPathLookup.childModules(prefix, position)
    companion object {
        fun getInstance(project: Project): XMakeApi = project.getService(XMakeApi::class.java)
    }
}

val Project.xmakeApi: XMakeApi
    get() = XMakeApi.getInstance(this)
