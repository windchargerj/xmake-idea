package io.xmake.lang.declarations.catalog

import com.intellij.openapi.project.Project
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.ModulePath
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.declarations.source.ApiDefinitionFactory
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.utils.info.XMakeApis

class ApiIndex(val project: Project) {

    @Volatile
    private var cachedModificationCount: Long = -1

    @Volatile
    private var cachedData: ApiIndexData? = null

    private val lock = Any()

    private fun data(): ApiIndexData {
        val apiService = ApiService.getInstance(project)
        val modificationCount = apiService.modificationTracker.modificationCount
        val currentModificationCount = cachedModificationCount
        val currentData = cachedData
        if (currentModificationCount == modificationCount && currentData != null) {
            return currentData
        }

        synchronized(lock) {
            val recheckedModificationCount = apiService.modificationTracker.modificationCount
            val recheckedData = cachedData
            if (cachedModificationCount == recheckedModificationCount && recheckedData != null) {
                return recheckedData
            }

            while (true) {
                val snapshot = apiService.snapshot()
                val snapshotCachedData = cachedData
                if (cachedModificationCount == snapshot.modificationCount && snapshotCachedData != null) {
                    return snapshotCachedData
                }

                val rebuilt = buildData(snapshot.apis)
                if (apiService.modificationTracker.modificationCount != snapshot.modificationCount) {
                    continue
                }

                cachedData = rebuilt
                cachedModificationCount = snapshot.modificationCount
                return rebuilt
            }
        }
    }

    private fun ApiService.snapshot(): ApiSnapshot {
        while (true) {
            val before = modificationTracker.modificationCount
            val apis = getApis()
            val after = modificationTracker.modificationCount
            if (before == after) {
                return ApiSnapshot(apis, after)
            }
        }
    }

    private fun buildData(apis: XMakeApis): ApiIndexData {
        val entries = ApiDefinitionFactory.create(apis)
        return ApiIndexData(
            entries = entries,
            entriesByName = entries.groupBy { it.name },
            entriesByType = entries.groupBy { it.type },
            moduleApisByPath = entries.filter { it.isModuleApi }.groupByModulePath(),
            instanceApisByType = entries.filter { it.isInstanceApi }.groupByInstanceType(),
            extensionModulesByName = entries.filter { it.type is ApiType.ScriptApi.ExtensionModuleApi }.groupByModulePath(),
            descriptionBuiltinModulesByName = entries.filter { it.type is ApiType.DescriptionApi.BuiltinModuleApi }.groupByModulePath(),
            scriptBuiltinModulesByName = entries.filter {
                it.type is ApiType.ScriptApi.BuiltinModuleApi && !it.modulePath.orEmpty().startsWith(CORE_MODULE_PREFIX)
            }.groupByModulePath()
        )
    }

    fun findByName(name: String): List<ApiModel> = data().entriesByName[name] ?: emptyList()

    fun findByFullName(fullName: String): ApiModel? = data().entries.firstOrNull { it.fullName == fullName }

    fun byType(type: ApiType): List<ApiModel> = data().entriesByType[type] ?: emptyList()

    fun scriptApis(): List<ApiModel> = data().entries.filter { it.isScriptApi }

    fun availableTopLevelCallables(context: ApiLookupView): List<ApiModel> {
        val data = data()
        val candidates = when (val domain = context.domain) {
            is XMakeDomain.Description -> {
                commonDescriptionCandidates(data) +
                    (if (context.isNamespaceRoot) data.entriesByType[ApiType.DescriptionApi.NamespaceEnd].orEmpty() else emptyList()) +
                    data.entriesByType[ApiType.DescriptionApi.ConfigurationItem(XMakeConfigurationDomainType.TARGET)].orEmpty()
            }

            is XMakeDomain.Configuration -> {
                val currentConfigurationDomainType = domain.type
                commonDescriptionCandidates(data) +
                    configurationDomainEndCandidates(data, currentConfigurationDomainType) +
                    data.entriesByType[ApiType.DescriptionApi.ConfigurationItem(currentConfigurationDomainType)].orEmpty()
            }

            is XMakeDomain.Script -> scriptApis()
        }
        return candidates.filter { candidate ->
            candidate.isAvailableIn(context) &&
                !candidate.isModuleApi &&
                !candidate.isInstanceApi
        }
    }

    private fun commonDescriptionCandidates(data: ApiIndexData): List<ApiModel> =
        data.entriesByType[ApiType.DescriptionApi.GlobalInterface].orEmpty() +
            XMakeDescriptionDomainRules.configurationDomainTypes.flatMap { configurationDomainType ->
                data.entriesByType[ApiType.DescriptionApi.ConfigurationDomainEntry(configurationDomainType)].orEmpty()
            } +
            data.entriesByType[ApiType.DescriptionApi.NamespaceEntry].orEmpty()

    private fun configurationDomainEndCandidates(
        data: ApiIndexData,
        configurationDomainType: XMakeConfigurationDomainType
    ): List<ApiModel> =
        data.entriesByType[ApiType.DescriptionApi.ConfigurationDomainEnd(configurationDomainType)].orEmpty()

    private fun List<ApiModel>.groupByModulePath(): Map<String, List<ApiModel>> =
        groupBy { it.modulePath.orEmpty() }

    private fun List<ApiModel>.groupByInstanceType(): Map<String, List<ApiModel>> =
        groupBy { it.instanceType.orEmpty() }

    fun moduleFunctions(modulePath: String, context: ApiLookupView): List<ApiModel> {
        val candidates = data().moduleApisByPath[modulePath].orEmpty()
        return candidates.filter { it.isAvailableIn(context) }
    }

    fun instanceApis(instanceType: String, context: ApiLookupView): List<ApiModel> {
        val apis = data().instanceApisByType[instanceType].orEmpty()
        return apis.filter { it.isAvailableIn(context) }
    }

    fun instanceTypes(): Set<String> = data().instanceApisByType.keys

    fun descriptionBuiltinModuleApis(modulePath: String): List<ApiModel> =
        data().descriptionBuiltinModulesByName[modulePath].orEmpty()

    fun scriptBuiltinModuleApis(modulePath: String): List<ApiModel> =
        data().scriptBuiltinModulesByName[modulePath].orEmpty()

    fun findApiByQualifiedSelector(selector: QualifiedApiSelector, context: ApiLookupView): ApiModel? {
        return when (selector) {
            is QualifiedApiSelector.ModuleFunction -> moduleFunctions(selector.modulePath, context)
                .firstOrNull { it.name == selector.functionName }

            is QualifiedApiSelector.InstanceMethod -> instanceApis(selector.instanceType, context)
                .firstOrNull { it.name == selector.methodName }
        }
    }

    fun indexedModulePaths(): Set<String> = data().moduleApisByPath.keys

    fun extensionModulePaths(): Set<String> = data().extensionModulesByName.keys

    fun descriptionBuiltinModulePaths(): Set<String> = data().descriptionBuiltinModulesByName.keys

    fun scriptBuiltinModulePaths(): Set<String> = data().scriptBuiltinModulesByName.keys

    fun visibleBuiltinModulePaths(context: ApiLookupView): Set<String> = when (context.domain) {
        is XMakeDomain.Script -> scriptBuiltinModulePaths()
        else -> descriptionBuiltinModulePaths()
    }

    fun isBuiltinModule(modulePath: String, context: ApiLookupView): Boolean =
        modulePath in visibleBuiltinModulePaths(context)

    fun isBuiltinModulePath(path: String, context: ApiLookupView): Boolean {
        if (path.isBlank()) {
            return false
        }
        val visibleModules = visibleBuiltinModulePaths(context)
        return path in visibleModules || ModulePath.childModules(visibleModules, path).isNotEmpty()
    }

    fun visibleChildModules(parentPath: String, context: ApiLookupView): List<String> {
        val data = data()
        val visibleModules = buildSet {
            addAll(visibleBuiltinModulePaths(context))
            if (context.domain is XMakeDomain.Script) {
                addAll(data.extensionModulesByName.keys)
            }
        }
        return ModulePath.childModules(visibleModules, parentPath)
    }

    fun extensionChildModules(parentPath: String): List<String> =
        ModulePath.childModules(data().extensionModulesByName.keys, parentPath)

    fun hasExtensionModuleOrChildren(modulePath: String): Boolean =
        isExtensionModulePath(modulePath) || extensionChildModules(modulePath).isNotEmpty()

    fun importableModuleApis(modulePath: String): List<ApiModel> =
        (data().extensionModulesByName[modulePath].orEmpty() + moduleFunctions(modulePath, ApiLookupView.SCRIPT_GLOBAL_ROOT))
            .distinctBy { it.fullName }

    fun isExtensionModulePath(modulePath: String): Boolean = modulePath in data().extensionModulesByName

    fun isDescriptionBuiltinModulePath(modulePath: String): Boolean = modulePath in data().descriptionBuiltinModulesByName

    fun configurationItems(configurationDomainType: XMakeConfigurationDomainType): List<ApiModel> =
        data().entriesByType[ApiType.DescriptionApi.ConfigurationItem(configurationDomainType)] ?: emptyList()

    fun getAllApis(): List<ApiModel> = data().entries

    companion object {
        private const val CORE_MODULE_PREFIX = "core."

        fun create(project: Project): ApiIndex = ApiIndex(project)
    }
}

private data class ApiIndexData(
    val entries: List<ApiModel>,
    val entriesByName: Map<String, List<ApiModel>>,
    val entriesByType: Map<ApiType, List<ApiModel>>,
    val moduleApisByPath: Map<String, List<ApiModel>>,
    val instanceApisByType: Map<String, List<ApiModel>>,
    val extensionModulesByName: Map<String, List<ApiModel>>,
    val descriptionBuiltinModulesByName: Map<String, List<ApiModel>>,
    val scriptBuiltinModulesByName: Map<String, List<ApiModel>>
)

private data class ApiSnapshot(
    val apis: XMakeApis,
    val modificationCount: Long
)
