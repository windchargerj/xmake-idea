package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.catalog.ApiIndex

/**
 * Immutable lookup view of imports available from a xmake Lua script location.
 * Produced by file-level import lookup queries.
 *
 * This is the central import lookup view used for import-aware resolution. It keeps two
 * name layers:
 * - receiver names: names usable as script-side module receivers
 * - bound names: names the IDE resolves in import-aware queries
 */
class ImportLookupView internal constructor(
    private val project: Project,
    private val api: XMakeApi,
    private val declarations: List<ImportDeclaration>,
    private val initialIssues: List<ImportIssue>,
    private val scriptDirectory: VirtualFile?
) {
    private val lookup: ApiIndex
        get() = api.lookup

    private data class BoundImportBinding(
        val binding: ImportBinding,
        val exports: ModuleExports
    )

    private data class ResolutionState(
        val bindings: List<BoundImportBinding>,
        val issues: List<ImportIssue>
    )

    private val moduleExportsResolver = ImportModuleExportsResolver(
        project = project,
        lookup = lookup,
        scriptDirectory = scriptDirectory
    )

    private val importBindings: List<ImportBinding> by lazy {
        declarations.flatMap(ImportDeclaration::toImportBindings)
    }

    private val resolutionState: ResolutionState by lazy {
        val issues = mutableListOf<ImportIssue>()
        val bindings = importBindings.mapNotNull { binding ->
            try {
                moduleExportsResolver.resolve(binding)?.let { exports ->
                    BoundImportBinding(binding, exports)
                }
            } catch (e: RuntimeException) {
                issues += ImportIssue.fromElement(
                    kind = ImportIssue.Kind.MODULE_EXPORTS_RESOLUTION_FAILED,
                    message = "Failed to resolve module exports for '${binding.modulePath}': ${e.message ?: e.javaClass.simpleName}",
                    element = binding.declarationElement
                )
                null
            }
        }
        ResolutionState(bindings = bindings, issues = issues)
    }

    private val visibleBindings: List<BoundImportBinding>
        get() = resolutionState.bindings

    private val receiverVisibleBindings: List<BoundImportBinding> by lazy {
        visibleBindings.filter { it.binding.kind != ImportBindingKind.RETURN_CAPTURE }
    }

    private val importedModulesByPath: Map<String, ImportedModuleView> by lazy {
        mergeImportedModules(visibleBindings)
    }

    private val receiverModulesByPath: Map<String, ImportedModuleView> by lazy {
        mergeImportedModules(receiverVisibleBindings)
    }

    private val boundBindingTargetsByName: Map<String, ImportBindingTargets> by lazy {
        bindingTargetsByName(
            bindings = visibleBindings,
            importedModulesByPath = importedModulesByPath,
            names = ImportBinding::boundNames
        )
    }

    private val receiverBindingTargetsByName: Map<String, ImportBindingTargets> by lazy {
        bindingTargetsByName(
            bindings = receiverVisibleBindings,
            importedModulesByPath = receiverModulesByPath,
            names = ImportBinding::receiverNames
        )
    }

    private val inheritedApiExposuresByName: Map<String, InheritedApiExposures> by lazy {
        val exposuresByName = linkedMapOf<String, MutableList<InheritedApiExposure>>()
        receiverVisibleBindings
            .filter { it.binding.inherit }
            .forEach { bound ->
                bound.exports.apis.forEach { exportedApi ->
                    exposuresByName.getOrPut(exportedApi.name) { mutableListOf() }
                        .add(
                            InheritedApiExposure(
                                api = exportedApi,
                                declarationElement = bound.binding.declarationElement
                            )
                        )
                }
            }
        exposuresByName.mapValuesNotNull { (_, candidates) ->
            toInheritedApiExposures(candidates)
        }
    }

    val issues: List<ImportIssue>
        get() = initialIssues + resolutionState.issues

    val hasIssues: Boolean
        get() = issues.isNotEmpty()

    val importedModules: List<ImportedModuleView>
        get() = importedModulesByPath.values.toList()

    val inheritedModules: List<ImportedModuleView>
        get() = receiverVisibleBindings.asSequence()
            .filter { it.binding.inherit }
            .mapNotNull { bound -> receiverModulesByPath[bound.exports.identifier] }
            .distinctBy { it.identifier }
            .toList()

    fun resolveBoundModule(identifier: String): ImportedModuleView? =
        resolveModule(
            identifier = identifier,
            importedModulesByPath = importedModulesByPath,
            bindingTargetsByName = boundBindingTargetsByName
        )

    fun resolveReceiverModule(identifier: String): ImportedModuleView? =
        resolveModule(
            identifier = identifier,
            importedModulesByPath = receiverModulesByPath,
            bindingTargetsByName = receiverBindingTargetsByName
        )

    fun resolveBindingTargets(identifier: String): ImportBindingTargets? =
        boundBindingTargetsByName[identifier]

    fun resolveReceiverBindingTargets(identifier: String): ImportBindingTargets? =
        receiverBindingTargetsByName[identifier]

    fun resolveInheritedApiExposures(name: String): InheritedApiExposures? =
        inheritedApiExposuresByName[name]

    private fun mergeImportedModules(bindings: List<BoundImportBinding>): Map<String, ImportedModuleView> {
        return bindings
            .groupBy { it.exports.identifier }
            .mapValues { (_, entries) ->
                mergeImportedModule(
                    exports = entries.first().exports,
                    bindings = entries.map { it.binding }
                )
            }
    }

    private fun mergeImportedModule(exports: ModuleExports, bindings: List<ImportBinding>): ImportedModuleView {
        val receiverNames = bindings.flatMap { it.receiverNames }.toSet()
        val boundNames = bindings.flatMap { it.boundNames }.toSet()
        val primaryReceiverName = bindings.asReversed().firstNotNullOfOrNull { it.primaryReceiverName }
            ?: receiverNames.firstOrNull()
        val primaryBoundName = bindings.asReversed().firstNotNullOfOrNull { it.primaryBoundName }
            ?: boundNames.firstOrNull()
        return exports.toImportedModuleView(
            primaryReceiverName = primaryReceiverName,
            primaryBoundName = primaryBoundName,
            boundNames = boundNames,
            secondaryReceiverNames = receiverNames - setOfNotNull(primaryReceiverName)
        )
    }

    private fun bindingTargetsByName(
        bindings: List<BoundImportBinding>,
        importedModulesByPath: Map<String, ImportedModuleView>,
        names: (ImportBinding) -> Collection<String>
    ): Map<String, ImportBindingTargets> {
        val targetsByName = linkedMapOf<String, MutableList<ImportBindingTarget>>()
        bindings.forEach { bound ->
            val module = importedModulesByPath[bound.exports.identifier] ?: return@forEach
            val target = ImportBindingTarget(
                module = module,
                declarationElement = bound.binding.declarationElement,
                origin = bound.binding.origin
            )
            names(bound.binding).forEach { bindingName ->
                targetsByName.getOrPut(bindingName) { mutableListOf() }.add(target)
            }
        }
        return targetsByName.mapValuesNotNull { (_, candidates) ->
            toImportBindingTargets(candidates)
        }
    }

    private fun resolveModule(
        identifier: String,
        importedModulesByPath: Map<String, ImportedModuleView>,
        bindingTargetsByName: Map<String, ImportBindingTargets>
    ): ImportedModuleView? =
        importedModulesByPath[identifier]
            ?: bindingTargetsByName[identifier]?.primary?.module

    private fun toImportBindingTargets(candidates: List<ImportBindingTarget>): ImportBindingTargets? {
        if (candidates.isEmpty()) return null
        val primary = candidates.last()
        val (shadowed, conflicts) = candidates.dropLast(1)
            .asReversed()
            .partition { it.module.identifier == primary.module.identifier }
        return ImportBindingTargets(
            primary = primary,
            shadowed = shadowed,
            conflicts = conflicts
        )
    }

    private fun toInheritedApiExposures(candidates: List<InheritedApiExposure>): InheritedApiExposures? {
        if (candidates.isEmpty()) return null
        val primary = candidates.last()
        val (shadowed, conflicts) = candidates.dropLast(1)
            .asReversed()
            .partition { it.api.fullName == primary.api.fullName }
        return InheritedApiExposures(
            primary = primary,
            shadowed = shadowed,
            conflicts = conflicts
        )
    }

    private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(
        transform: (Map.Entry<K, V>) -> R?
    ): Map<K, R> {
        val result = linkedMapOf<K, R>()
        for (entry in entries) {
            transform(entry)?.let { value ->
                result[entry.key] = value
            }
        }
        return result
    }
}
