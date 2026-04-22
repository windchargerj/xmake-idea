package io.xmake.lang.analysis.xmake

import com.intellij.openapi.application.ReadAction
import io.xmake.lang.analysis.lua.LuaCallSyntaxKind
import io.xmake.lang.analysis.lua.LuaIncompleteMemberAccess
import io.xmake.lang.analysis.lua.LuaMemberAccessResolver
import io.xmake.lang.analysis.lua.LuaTypeInference
import io.xmake.lang.analysis.lua.isEditorRecoveryCallHead
import io.xmake.lang.analysis.model.CallAnalysis
import io.xmake.lang.analysis.model.CallAnalysisSource
import io.xmake.lang.analysis.model.CallableIntentState
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.declarations.model.ApiType
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal object XMakeCallableAnalyzer {

    private enum class ResolutionMode(val intent: CallableIntentState) {
        DIRECT_CALL(CallableIntentState.DIRECT_CALL),
        EDITOR_RECOVERY_CALL_HEAD(CallableIntentState.EDITOR_RECOVERY_CALL_HEAD)
    }

    fun analyzeEditorRecoveryCallHead(element: XMakeLuaIdentifier): CallAnalysis? =
        analyze(element, ResolutionMode.EDITOR_RECOVERY_CALL_HEAD)

    fun analyzeDirectCall(element: XMakeLuaIdentifier, callKind: LuaCallSyntaxKind): CallAnalysis? =
        analyze(element, ResolutionMode.DIRECT_CALL, callKind)

    private fun analyze(
        element: XMakeLuaIdentifier,
        mode: ResolutionMode,
        callKind: LuaCallSyntaxKind? = null
    ): CallAnalysis? {
        return ReadAction.compute<CallAnalysis?, RuntimeException> {
            val api = element.project.xmakeApi
            val name = element.name
            val apiContext = ApiLookupContext.forIdentifier(element)
            val currentScope = apiContext.domain
            val file = element.containingFile as? XMakeLuaFile

            when (mode) {
                ResolutionMode.DIRECT_CALL -> {
                    when (callKind) {
                        LuaCallSyntaxKind.None, null -> return@compute null
                        LuaCallSyntaxKind.MemberCall, LuaCallSyntaxKind.PlainCall -> Unit
                    }

                    val qualifiedSelector = element.qualifiedApiSelector(apiContext)
                    if (qualifiedSelector != null) {
                        when (XMakeCallRules.classifyQualifiedCallShape(qualifiedSelector)) {
                            XMakeCallRules.CallShape.MODULE_MEMBER ->
                                if (XMakeCallRules.isQualifiedCallKnown(api, qualifiedSelector, apiContext)) {
                                    return@compute semanticInfo(mode, IdentifierSemanticKind.MODULE_CALL)
                                }

                            XMakeCallRules.CallShape.INSTANCE_METHOD ->
                                if (XMakeCallRules.isQualifiedCallKnown(api, qualifiedSelector, apiContext)) {
                                    return@compute semanticInfo(mode, IdentifierSemanticKind.INSTANCE_METHOD)
                                }

                            XMakeCallRules.CallShape.UNKNOWN -> Unit
                        }
                    }

                    resolveDirectMemberCallType(element, name, apiContext)?.let {
                        return@compute semanticInfo(mode, it)
                    }
                }

                ResolutionMode.EDITOR_RECOVERY_CALL_HEAD -> {
                    resolveEditorRecoveryMemberCallType(element, apiContext, file)?.let {
                        return@compute semanticInfo(mode, it)
                    }
                    if (!element.isEditorRecoveryCallHead) {
                        return@compute null
                    }
                }
            }

            classifyUnqualifiedCallable(
                element = element,
                api = api,
                name = name,
                currentScope = currentScope,
                apiContext = apiContext
            )?.let { return@compute adaptIntent(it, mode) }

            return@compute null
        }
    }

    private fun resolveDirectMemberCallType(
        element: XMakeLuaIdentifier,
        memberName: String,
        context: ApiLookupView
    ): IdentifierSemanticKind? {
        val memberCall = LuaMemberAccessResolver.findDirectMemberAccess(element) ?: return null
        val receiverType = LuaTypeInference.inferType(memberCall.receiver, context) ?: return null
        val api = element.project.xmakeApi

        val callShape = XMakeCallRules.classifyMemberCallShape(receiverType, memberCall.separator)
        if (!XMakeCallRules.isMemberCallKnown(api, receiverType, memberName, memberCall.separator, context)) {
            return null
        }

        return when (callShape) {
            XMakeCallRules.CallShape.MODULE_MEMBER -> IdentifierSemanticKind.MODULE_CALL
            XMakeCallRules.CallShape.INSTANCE_METHOD -> IdentifierSemanticKind.INSTANCE_METHOD
            XMakeCallRules.CallShape.UNKNOWN -> null
        }
    }

    private fun classifyUnqualifiedCallable(
        element: XMakeLuaIdentifier,
        api: io.xmake.lang.declarations.XMakeApi,
        name: String,
        currentScope: XMakeDomain,
        apiContext: ApiLookupView
    ): CallAnalysis? {
        XMakeStructuralCallClassifier.classify(name, currentScope)?.let { structuralKind ->
            return CallAnalysis.structural(
                kind = structuralKind,
                intent = CallableIntentState.DIRECT_CALL
            )
        }

        val file = element.containingFile as? XMakeLuaFile
        val unqualifiedApi = api.findUnqualifiedApi(name, apiContext, file, element)
        return when {
            unqualifiedApi != null -> CallAnalysis.semantic(
                kind = semanticKindForApi(unqualifiedApi),
                intent = CallableIntentState.DIRECT_CALL
            )

            else -> null
        }
    }

    private fun semanticKindForApi(api: ApiModel): IdentifierSemanticKind =
        when (api.type) {
            is ApiType.ScriptApi.TopLevelApi -> IdentifierSemanticKind.SCRIPT_BUILTIN_FUNCTION_CALL
            is ApiType.ScriptApi -> IdentifierSemanticKind.SCRIPT_API_CALL
            is ApiType.DescriptionApi.GlobalInterface ->
                if (XMakeDescriptionDomainRules.isBuiltinFunctionInterface(api.name)) {
                    IdentifierSemanticKind.DESCRIPTION_BUILTIN_FUNCTION_CALL
                } else {
                    IdentifierSemanticKind.DESCRIPTION_API_CALL
                }

            else -> IdentifierSemanticKind.DESCRIPTION_API_CALL
        }

    private fun resolveEditorRecoveryMemberCallType(
        element: XMakeLuaIdentifier,
        context: ApiLookupView,
        file: XMakeLuaFile?
    ): IdentifierSemanticKind? {
        // Heuristic boundary: records editor recovery without promoting it to a stable semantic fact.
        val memberAccess = LuaMemberAccessResolver.findIncompleteMemberAccess(element) ?: return null
        val api = element.project.xmakeApi

        val receiverType = memberAccess.receiver
            ?.let { LuaTypeInference.inferType(it, context) }
            ?: inferEditorRecoveryMemberReceiverType(api, file, element, memberAccess, context)
            ?: return null

        val callShape = XMakeCallRules.classifyMemberCallShape(receiverType, memberAccess.separator)
        if (!XMakeCallRules.isMemberCallKnown(api, receiverType, element.name, memberAccess.separator, context)) {
            return null
        }

        return when (callShape) {
            XMakeCallRules.CallShape.MODULE_MEMBER -> IdentifierSemanticKind.MODULE_CALL
            XMakeCallRules.CallShape.INSTANCE_METHOD -> IdentifierSemanticKind.INSTANCE_METHOD
            XMakeCallRules.CallShape.UNKNOWN -> null
        }
    }

    private fun inferEditorRecoveryMemberReceiverType(
        api: io.xmake.lang.declarations.XMakeApi,
        file: XMakeLuaFile?,
        element: XMakeLuaIdentifier,
        memberAccess: LuaIncompleteMemberAccess,
        context: ApiLookupView
    ): XMakeType? {
        resolveEditorRecoveryMemberReceiverSymbolType(element, memberAccess, context)?.let { return it }

        return when (memberAccess.separator) {
            "." -> {
                val modulePath = api.resolveVisibleModulePath(
                    modulePath = memberAccess.receiverPath,
                    context = context,
                    file = file,
                    place = element
                ) ?: return null
                XMakeType.Module(modulePath, context)
            }

            else -> null
        }
    }

    private fun resolveEditorRecoveryMemberReceiverSymbolType(
        element: XMakeLuaIdentifier,
        memberAccess: LuaIncompleteMemberAccess,
        context: ApiLookupView
    ): XMakeType? {
        memberAccess.receiver?.let { LuaTypeInference.inferType(it, context) }?.let { return it }

        if (memberAccess.receiverPath.contains('.') || memberAccess.receiverPath.contains(':')) {
            return null
        }

        return when (val symbol =
            VisibleSymbolResolver.find(element, memberAccess.receiverPath, memberAccess.receiverEndOffset, context)) {
            is VisibleSymbol.Local -> LuaTypeInference.inferType(symbol.declaration, context)
            is VisibleSymbol.ImportedModule,
            is VisibleSymbol.InheritedApi,
            is VisibleSymbol.Synthetic,
            is VisibleSymbol.BuiltinModule,
            is VisibleSymbol.BuiltinApi -> symbol.inferredType

            null -> null
        }
    }

    private fun semanticInfo(
        mode: ResolutionMode,
        kind: IdentifierSemanticKind
    ): CallAnalysis = CallAnalysis.semantic(kind, mode.intent, source = mode.source)

    private fun adaptIntent(
        analysis: CallAnalysis,
        mode: ResolutionMode
    ): CallAnalysis {
        return if (analysis.intent == mode.intent) {
            analysis
        } else {
            CallAnalysis(
                semanticKind = analysis.semanticKind,
                structuralKind = analysis.structuralKind,
                intent = mode.intent,
                resolvedElement = analysis.resolvedElement,
                source = mode.source
            )
        }
    }

    private val ResolutionMode.source: CallAnalysisSource
        get() = when (this) {
            ResolutionMode.DIRECT_CALL -> CallAnalysisSource.STABLE_PSI
            ResolutionMode.EDITOR_RECOVERY_CALL_HEAD -> CallAnalysisSource.EDITOR_RECOVERY
        }
}
