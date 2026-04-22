package io.xmake.lang.resolution

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.declarations.source.ApiDefinitionFactory
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.utils.info.XMakeApis

private const val SYNTHETIC_FUNCTION_PREFIX = "function "
private const val SYNTHETIC_FILE_HEADER = "-- @xmake:synthetic-api-declarations\n\n"
private const val SYNTHETIC_MARKER_KEY_ID = "io.xmake.lang.resolution.synthetic.api.declarations.file"
private val SYNTHETIC_FILE_MARKER_KEY = Key.create<Boolean>(SYNTHETIC_MARKER_KEY_ID)

@Service(Service.Level.PROJECT)
class XMakeApiDeclarationService(private val project: Project) {

    @Volatile
    private var cachedState: ApiDeclarationIndexState? = null

    private val lock = Any()

    fun declarationTarget(api: ApiModel): XMakeLuaIdentifier? =
        currentIndex().declarationTarget(api.fullName)

    fun isSyntheticApiDeclaration(element: XMakeLuaIdentifier): Boolean {
        val file = element.containingFile as? XMakeLuaFile ?: return false
        if (file.getUserData(SYNTHETIC_FILE_MARKER_KEY) == true) {
            return true
        }
        if (file.name != SYNTHETIC_FILE_NAME) {
            return false
        }
        return file.text.startsWith(SYNTHETIC_FILE_HEADER)
    }

    private fun currentIndex(): ApiDeclarationIndex {
        val apiService = ApiService.getInstance(project)
        val modificationCount = apiService.modificationTracker.modificationCount
        cachedState?.takeIf { it.modificationCount == modificationCount }?.let { return it.index }

        synchronized(lock) {
            cachedState?.takeIf { it.modificationCount == modificationCount }?.let { return it.index }
            val index = buildIndex(apiService.getApis())
            cachedState = ApiDeclarationIndexState(
                modificationCount = modificationCount,
                index = index
            )
            return index
        }
    }

    private fun buildIndex(apis: XMakeApis): ApiDeclarationIndex {
        val entries = ApiDefinitionFactory.create(apis)
            .distinctBy(ApiModel::fullName)
            .sortedBy(ApiModel::fullName)
            .map(::toSyntheticEntry)

        val projection = SyntheticDeclarationProjectionBuilder.build(project, entries)
        val offsetsByFullName = projection.entries.associate { it.fullName to it.identifierOffset }
        return ApiDeclarationIndex(
            file = projection.file,
            targets = offsetsByFullName
        )
    }

    private fun resolveDeclarationIdentifier(
        file: XMakeLuaFile,
        fullName: String,
        identifierOffset: Int
    ): XMakeLuaIdentifier {
        val leaf = requireNotNull(file.findElementAt(identifierOffset)) {
            "Missing synthetic declaration leaf at offset $identifierOffset for $fullName"
        }
        val identifier = PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
            ?: (leaf as? XMakeLuaIdentifier)
            ?: error("Missing synthetic declaration identifier at offset $identifierOffset for $fullName")
        return identifier
    }

    private fun declarationName(api: ApiModel): String =
        when (api.type) {
            is ApiType.DescriptionApi.GlobalInterface,
            is ApiType.DescriptionApi.ConfigurationDomainEntry,
            is ApiType.DescriptionApi.ConfigurationDomainEnd,
            is ApiType.DescriptionApi.NamespaceEntry,
            is ApiType.DescriptionApi.NamespaceEnd,
            is ApiType.ScriptApi.TopLevelApi -> api.name

            is ApiType.DescriptionApi.ConfigurationItem,
            is ApiType.DescriptionApi.BuiltinModuleApi,
            is ApiType.ScriptApi.BuiltinModuleApi,
            is ApiType.ScriptApi.ExtensionModuleApi,
            is ApiType.ScriptApi.InstanceApi -> api.fullName
        }

    private fun toSyntheticEntry(api: ApiModel): SyntheticDeclarationEntry {
        val declarationName = declarationName(api)
        val identifierRelativeOffset = identifierStartInDeclarationName(declarationName)
        return SyntheticDeclarationEntry(
            fullName = api.fullName,
            declarationName = declarationName,
            expectedIdentifier = api.name,
            identifierRelativeOffset = identifierRelativeOffset
        )
    }

    private fun identifierStartInDeclarationName(declarationName: String): Int {
        val separator = maxOf(declarationName.lastIndexOf('.'), declarationName.lastIndexOf(':'))
        return separator + 1
    }

    companion object {
        const val SYNTHETIC_FILE_NAME = "__xmake_api_declarations__.lua"

        fun getInstance(project: Project): XMakeApiDeclarationService =
            project.getService(XMakeApiDeclarationService::class.java)
    }

    private data class ApiDeclarationIndex(
        val file: XMakeLuaFile,
        val targets: Map<String, Int>
    ) {
        fun declarationTarget(fullName: String): XMakeLuaIdentifier? {
            val offset = targets[fullName] ?: return null
            return getInstance(file.project).resolveDeclarationIdentifier(file, fullName, offset)
        }
    }

    private data class ApiDeclarationIndexState(
        val modificationCount: Long,
        val index: ApiDeclarationIndex
    )

    private data class SyntheticDeclarationEntry(
        val fullName: String,
        val declarationName: String,
        val expectedIdentifier: String,
        val identifierRelativeOffset: Int,
        val identifierOffset: Int = -1
    )

    private data class SyntheticDeclarationProjection(
        val file: XMakeLuaFile,
        val entries: List<SyntheticDeclarationEntry>
    )

    private object SyntheticDeclarationProjectionBuilder {
        fun build(project: Project, entries: List<SyntheticDeclarationEntry>): SyntheticDeclarationProjection {
            val projectedEntries = mutableListOf<SyntheticDeclarationEntry>()
            val content = buildString {
                append(SYNTHETIC_FILE_HEADER)
                entries.forEach { entry ->
                    append("-- ").append(entry.fullName).append('\n')
                    val declarationStart = length
                    append(SYNTHETIC_FUNCTION_PREFIX).append(entry.declarationName).append("() end\n\n")
                    projectedEntries += entry.copy(
                        identifierOffset = declarationStart + SYNTHETIC_FUNCTION_PREFIX.length + entry.identifierRelativeOffset
                    )
                }
            }

            val file = PsiFileFactory.getInstance(project)
                .createFileFromText(
                    SYNTHETIC_FILE_NAME,
                    XMakeLuaLanguage.INSTANCE,
                    content
                ) as XMakeLuaFile
            file.putUserData(SYNTHETIC_FILE_MARKER_KEY, true)
            return SyntheticDeclarationProjection(file = file, entries = projectedEntries)
        }
    }
}
