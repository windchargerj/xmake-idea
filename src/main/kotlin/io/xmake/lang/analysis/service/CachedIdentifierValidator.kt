package io.xmake.lang.analysis.service

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.analysis.lua.LuaSymbolResolver
import io.xmake.lang.analysis.model.CallAnalysis
import io.xmake.lang.analysis.model.Severity
import io.xmake.lang.analysis.model.ValidationError
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiAvailabilityPolicy
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.scope.issue.ScopeIssue
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaStatement

/**
 * Cached identifier validator implementation.
 *
 * Uses [CachedAnalyzer] + [com.intellij.psi.util.CachedValuesManager] for caching,
 * bound to PSI modification tracker for automatic invalidation.
 */
internal class CachedIdentifierValidator : CachedAnalyzer<ValidationError?>() {

    override val cacheKey: Key<CachedValue<ValidationError?>> = CACHE_KEY

    fun shouldValidate(element: XMakeLuaIdentifier): Boolean {
        return PsiPredicates.isFunctionCall(element) ||
            PsiPredicates.isGotoLabelReference(element) ||
            PsiPredicates.isLabel(element)
    }

    fun validate(
        element: XMakeLuaIdentifier,
        callableInfo: CallAnalysis? = null
    ): ValidationError? {
        ProgressManager.checkCanceled()
        if (!shouldValidate(element)) return null

        return getOrPut(element) { computeValidation(element, callableInfo) }
    }

    private fun computeValidation(
        element: XMakeLuaIdentifier,
        callableInfo: CallAnalysis?
    ): ValidationError? {
        when {
            PsiPredicates.isGotoLabelReference(element) -> return validateGotoLabelReference(element)
            PsiPredicates.isLabel(element) -> return validateLabelDeclaration(element)
            PsiPredicates.isFunctionCall(element) -> return validateApiCall(element, callableInfo)
        }

        return null
    }

    private fun validateGotoLabelReference(element: XMakeLuaIdentifier): ValidationError? {
        val name = element.name
        val label = LuaSymbolResolver.resolveLabelReference(element)
            ?: return ValidationError(
                message = "Label '$name' is not visible in this block or function",
                availableScopes = emptyList(),
                severity = Severity.ERROR,
                errorElement = element
            )

        val blockedBy = findLocalScopeBarrier(element, label)
        if (blockedBy != null) {
            return ValidationError(
                message = "goto '$name' jumps into the domain of local '${blockedBy.name}'",
                availableScopes = emptyList(),
                severity = Severity.ERROR,
                errorElement = element
            )
        }

        return null
    }

    private fun validateLabelDeclaration(element: XMakeLuaIdentifier): ValidationError? {
        val name = element.name
        val conflict = findVisibleLabelConflict(element) ?: return null
        return ValidationError(
            message = "Label '$name' conflicts with visible label '${conflict.name}'",
            availableScopes = emptyList(),
            severity = Severity.ERROR,
            errorElement = element
        )
    }

    private fun validateApiCall(
        element: XMakeLuaIdentifier,
        callableInfo: CallAnalysis?
    ): ValidationError? {
        if (callableInfo?.bypassesApiAvailabilityValidation == true) {
            return null
        }

        if (LuaSymbolResolver.resolveVisibleElement(element) != null) {
            return null
        }

        val name = element.name
        val project = element.project
        val api = project.xmakeApi

        // Uses XMakeScopeQuery (precise): domain-issue detection requires structural accuracy, not heuristic tolerance.
        XMakeScopeQuery.issuesAt(element)
            .firstOrNull { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END }
            ?.let { issue ->
                return ValidationError(
                    message = issue.message,
                    availableScopes = emptyList(),
                    severity = Severity.ERROR,
                    errorElement = element
                )
            }

        val apiContext = ApiLookupContext.forIdentifier(element)
        val apiModels = api.findApisByName(name)
        val availableModels = apiModels.filter { ApiAvailabilityPolicy.isAvailable(it, apiContext) }
        if (availableModels.isNotEmpty()) return null

        val file = element.containingFile as? XMakeLuaFile
        val importedModule = file?.let { api.imports.findReceiverModule(it, name, element) }
        if (importedModule != null && importedModule.apis.isNotEmpty()) {
            return null
        }

        if (apiModels.isEmpty()) return null
        val availableScopes = ApiAvailabilityPolicy.describeAvailabilities(apiModels)
            .ifEmpty { listOf("none") }
        val message =
            "API '$name' is not available in ${ApiAvailabilityPolicy.describeContext(apiContext)}. " +
                "Available in: ${availableScopes.joinToString(", ")}"
        return ValidationError(
            message = message,
            availableScopes = availableScopes,
            severity = Severity.ERROR,
            errorElement = element
        )
    }

    private fun findLocalScopeBarrier(gotoReference: XMakeLuaIdentifier, label: XMakeLuaIdentifier): XMakeLuaIdentifier? {
        val root = LuaSymbolResolver.labelVisibilityRoot(gotoReference) ?: return null
        val labelAnchor = statementAnchor(label)
        val gotoAnchor = statementAnchor(gotoReference)

        return PsiTreeUtil.findChildrenOfType(root, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it !== gotoReference && it !== label }
            .filter { PsiPredicates.isLocalVariable(it) || PsiPredicates.isBindableFunctionDeclarationName(it) }
            .filter { declarationEnclosesLabel(it, labelAnchor) }
            .filter { gotoAnchor.textRange.startOffset < declarationVisibilityStartOffset(it) }
            .filter { labelAnchor.textRange.startOffset >= declarationVisibilityStartOffset(it) }
            .sortedBy { it.textOffset }
            .firstOrNull()
    }

    private fun declarationEnclosesLabel(declaration: XMakeLuaIdentifier, labelAnchor: PsiElement): Boolean {
        val scope = PsiPredicates.declarationScope(declaration) ?: return false
        return PsiTreeUtil.isAncestor(scope, labelAnchor, false)
    }

    private fun declarationVisibilityStartOffset(declaration: XMakeLuaIdentifier): Int {
        if (PsiPredicates.isForLoopVariable(declaration) || PsiPredicates.isParameter(declaration)) {
            return (PsiPredicates.declarationScope(declaration) ?: declaration).textRange.startOffset
        }

        val scope = PsiPredicates.declarationScope(declaration) ?: return declaration.textRange.startOffset
        val declarationStatement = PsiTreeUtil.getParentOfType(declaration, LuaStatement::class.java)
            ?: return scope.textRange.startOffset
        val nextStatement = PsiTreeUtil.getNextSiblingOfType(declarationStatement, LuaStatement::class.java)

        return nextStatement?.textRange?.startOffset ?: scope.textRange.endOffset
    }

    private fun findVisibleLabelConflict(element: XMakeLuaIdentifier): XMakeLuaIdentifier? {
        val name = element.name
        var currentScope = true

        for (scope in LuaSymbolResolver.labelScopeChain(element)) {
            ProgressManager.checkCanceled()
            val candidates = labelsDeclaredDirectlyInScope(scope, name)
                .filter { it !== element }
                .sortedBy { it.textOffset }

            val conflict = if (currentScope) {
                candidates.firstOrNull { it.textOffset < element.textOffset }
            } else {
                candidates.firstOrNull()
            }
            if (conflict != null) {
                return conflict
            }
            currentScope = false
        }

        return null
    }

    private fun labelsDeclaredDirectlyInScope(scope: PsiElement, name: String): Sequence<XMakeLuaIdentifier> {
        return PsiTreeUtil.findChildrenOfType(scope, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.name == name }
            .filter { PsiPredicates.isLabel(it) }
            .filter { PsiPredicates.declarationScope(it) == scope }
    }

    private fun statementAnchor(element: XMakeLuaIdentifier): PsiElement {
        return PsiTreeUtil.getParentOfType(element, LuaStatement::class.java) ?: element
    }

    companion object {
        private val CACHE_KEY = Key.create<CachedValue<ValidationError?>>("xmake.identifier.validation")
    }
}
