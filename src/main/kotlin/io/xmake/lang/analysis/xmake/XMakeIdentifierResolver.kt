package io.xmake.lang.analysis.xmake

import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.declarations.resolution.ApiResolution
import io.xmake.lang.declarations.resolution.ApiResolutionResult
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.ApiAvailability
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.analysis.lua.isNestedCallQualifier
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.analysis.lua.LuaCallChainResolver
import io.xmake.lang.analysis.lua.LuaMemberAccessResolver
import io.xmake.lang.analysis.lua.LuaSymbolResolver
import io.xmake.lang.analysis.lua.LuaTypeInference

object XMakeIdentifierResolver {

    fun resolveIdentifier(
        api: XMakeApi,
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView
    ): ApiResolutionResult {
        val qualifiedSelector = identifier.qualifiedApiSelector(context)
        return if (qualifiedSelector != null) {
            when (val qualifiedResolution = api.resolveQualifiedSelector(qualifiedSelector, context)) {
                is ApiResolutionResult.Resolved -> qualifiedResolution
                is ApiResolutionResult.NotFound ->
                    resolveImportedModuleCall(api, identifier, context)
                        ?: ApiResolutionResult.NotFound
            }
        } else {
            when (val symbol = VisibleSymbolResolver.resolve(identifier, context)) {
                is VisibleSymbol.InheritedApi ->
                    return ApiResolutionResult.Resolved(ApiResolution(api = symbol.api))

                is VisibleSymbol.BuiltinApi ->
                    return ApiResolutionResult.Resolved(ApiResolution(api = symbol.api))

                is VisibleSymbol.BuiltinModule ->
                    return ApiResolutionResult.NotFound

                is VisibleSymbol.Local,
                is VisibleSymbol.ImportedModule,
                is VisibleSymbol.Synthetic -> {
                    // Local variables shadow xmake API names (Lua runtime behavior confirmed via sandbox.lua)
                    return ApiResolutionResult.NotFound
                }

                null -> Unit
            }
            resolveImportedModuleCall(api, identifier, context)
                ?: ApiResolutionResult.NotFound
        }
    }

    fun isUnresolvedApiCall(api: XMakeApi, identifier: XMakeLuaIdentifier, context: ApiLookupView): Boolean {
        if (!PsiPredicates.isFunctionCall(identifier)) {
            return false
        }

        return when (resolveIdentifier(api, identifier, context)) {
            is ApiResolutionResult.Resolved -> false
            is ApiResolutionResult.NotFound -> {
                val memberAccess = LuaMemberAccessResolver.findDirectMemberAccess(identifier)
                if (memberAccess != null && LuaTypeInference.inferType(memberAccess.receiver, context) == null) {
                    return false
                }
                true
            }
        }
    }

    fun isUnresolvedNestedQualifier(api: XMakeApi, identifier: XMakeLuaIdentifier, context: ApiLookupView): Boolean {
        if (!identifier.isNestedCallQualifier) {
            return false
        }

        val chain = LuaCallChainResolver.resolve(identifier) ?: return false
        if (chain.identifiers.firstOrNull() !== identifier) {
            return false
        }

        if (LuaSymbolResolver.resolveVisibleElement(identifier) != null) {
            return false
        }
        val file = identifier.containingFile as? XMakeLuaFile ?: return false
        if (LuaTypeInference.inferType(identifier, context) != null) {
            return false
        }
        return !api.isVisibleModulePath(identifier.text, context, file, identifier)
    }

    private fun resolveImportedModuleCall(api: XMakeApi, identifier: XMakeLuaIdentifier, context: ApiLookupView): ApiResolutionResult? {
        val file = identifier.containingFile as? XMakeLuaFile ?: return null
        val chain = LuaCallChainResolver.resolve(identifier) ?: return null
        if (chain.targetIdentifier !== identifier) {
            return null
        }

        val pathSegments = chain.pathSegments
        if (pathSegments.size < 2) {
            return null
        }

        val receiverPath = pathSegments.dropLast(1).joinToString(".")
        val memberName = pathSegments.last()
        if (context.domain !is XMakeDomain.Script) {
            return null
        }
        val importedModule = api.imports.findReceiverModule(file, receiverPath.substringBefore('.'), identifier)
        val visibleModulePath = resolveSyntheticModulePath(chain.identifiers.firstOrNull(), receiverPath)
            ?: api.resolveVisibleModulePath(receiverPath, context, file, identifier)
            ?: return null

        importedModule
            ?.takeIf { it.identifier == visibleModulePath }
            ?.declarations
            ?.firstOrNull { it.name == memberName }
            ?.let { declaration ->
                val apiModel = importedModule.apis.firstOrNull { it.name == memberName }
                    ?: ApiModel(
                        fullName = "$visibleModulePath.$memberName",
                        name = memberName,
                        type = ApiType.ScriptApi.ExtensionModuleApi(visibleModulePath),
                        availability = ApiAvailability.SCRIPT_ONLY
                    )
                return ApiResolutionResult.Resolved(
                    ApiResolution(
                        api = apiModel,
                        virtualElement = declaration.declarationElement
                    )
                )
            }

        return api.resolveQualifiedSelector(
            QualifiedApiSelector.ModuleFunction(
                modulePath = visibleModulePath,
                functionName = memberName
            ),
            context
        )
    }

    private fun resolveSyntheticModulePath(receiver: XMakeLuaIdentifier?, receiverPath: String): String? {
        val firstSegment = receiverPath.substringBefore('.')
        val symbol = receiver
            ?.takeIf { it.text == firstSegment }
            ?.let { VisibleSymbolResolver.resolve(it, ApiLookupContext.forIdentifier(it)) }
        val moduleSymbol = (symbol as? VisibleSymbol.ImportedModule)?.symbol
            ?: return null
        val suffix = receiverPath.substringAfter('.', "")
        return if (suffix.isEmpty()) {
            moduleSymbol.module.identifier
        } else {
            "${moduleSymbol.module.identifier}.$suffix"
        }
    }
}
