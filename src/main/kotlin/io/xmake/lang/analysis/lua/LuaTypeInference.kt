package io.xmake.lang.analysis.lua

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.analysis.xmake.XMakeVerifiedHookParameterTypes
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.import.ImportCallParser
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.resolver.TypeResolver
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaExpression
import io.xmake.lang.syntax.psi.lua.LuaExpressionList
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaFunctionDefinition
import io.xmake.lang.syntax.psi.lua.LuaReturnStatement
import io.xmake.lang.syntax.psi.lua.LuaStatement
import io.xmake.lang.syntax.psi.lua.LuaVariableList

object LuaTypeInference {
    private val LOG = logger<LuaTypeInference>()

    fun inferType(identifier: XMakeLuaIdentifier): XMakeType? =
        inferType(identifier, defaultContext(identifier))

    fun inferType(identifier: XMakeLuaIdentifier, context: ApiLookupView): XMakeType? {
        inferCallChainTargetType(identifier, context)?.let { return it }

        val target = when {
            PsiPredicates.isLocalVariable(identifier) || PsiPredicates.isAssignmentTarget(identifier) ->
                VisibleSymbol.Local(identifier, inferredType = null)

            else -> VisibleSymbolResolver.resolve(identifier, context)
                ?: VisibleSymbol.Local(identifier, inferredType = null)
        }
        return inferFromVisibleSymbol(target, context, mutableSetOf())
    }

    private fun inferFromVisibleSymbol(
        visibleSymbol: VisibleSymbol,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>
    ): XMakeType? = when (visibleSymbol) {
        is VisibleSymbol.Local -> inferFromDeclaration(visibleSymbol.declaration, context, visited)
        is VisibleSymbol.ImportedModule,
        is VisibleSymbol.InheritedApi,
        is VisibleSymbol.Synthetic,
        is VisibleSymbol.BuiltinModule,
        is VisibleSymbol.BuiltinApi -> visibleSymbol.inferredType
    }

    private fun inferFromDeclaration(
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>
    ): XMakeType? {
        if (!visited.add(identifier)) {
            return null
        }

        val typeResolver = safeTypeResolver(identifier) ?: return null

        inferCallChainTargetType(identifier, context, visited, typeResolver)?.let { return it }

        if (PsiPredicates.isBindableFunctionDeclarationName(identifier)) {
            val returnedExpression = findReturnedExpression(identifier) ?: return null
            return inferExpressionType(returnedExpression, context, visited, typeResolver)
        }

        if (PsiPredicates.isLocalVariable(identifier) || PsiPredicates.isAssignmentTarget(identifier)) {
            val initializer = findInitializerExpression(identifier) ?: return null
            return inferExpressionType(initializer, context, visited, typeResolver)
        }

        return resolveIdentifierType(identifier, context, typeResolver)
    }

    private fun resolveIdentifierType(
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView,
        typeResolver: TypeResolver
    ): XMakeType? {
        val api = safeApi(identifier)
        if (api != null) {
            XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, api)?.let { return it }
        } else {
            XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier)?.let { return it }
        }
        VisibleSymbolResolver.resolve(identifier, context)
            ?.inferredType
            ?.let { return it }
        typeResolver.resolveType(identifier.text, context)?.let { return it }
        return null
    }

    private fun inferExpressionType(
        expression: com.intellij.psi.PsiElement,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        inferImportExpressionType(expression)?.let { return it }
        inferChainedExpressionType(expression, context, visited, typeResolver)?.let { return it }

        val calledIdentifier = PsiTreeUtil.findChildrenOfType(expression, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { PsiPredicates.isFunctionCall(it) }
            .sortedBy { it.textOffset }
            .lastOrNull()
        if (calledIdentifier != null) {
            val functionCall = PsiTreeUtil.getParentOfType(calledIdentifier, LuaFunctionCall::class.java)
            if (functionCall != null) {
                val memberAccess = LuaMemberAccessResolver.findDirectMemberAccess(calledIdentifier)
                if (memberAccess != null) {
                    val receiverType = inferFromDeclaration(memberAccess.receiver, context, visited)
                    if (receiverType != null) {
                        typeResolver.resolveMemberReturnType(receiverType, calledIdentifier.text, memberAccess.separator)
                            ?.let { return it }
                    }
                }

                resolveIdentifierType(calledIdentifier, context, typeResolver)?.let { return it }

                val visibleCall = VisibleSymbolResolver.resolve(calledIdentifier, context)
                if (visibleCall != null) {
                    return inferFromVisibleSymbol(visibleCall, context, visited)
                }
            }
        }

        val initializer = PsiTreeUtil.findChildOfType(expression, XMakeLuaIdentifier::class.java) ?: return null
        resolveIdentifierType(initializer, context, typeResolver)?.let { return it }

        val visibleInitializer = VisibleSymbolResolver.resolve(initializer, context) ?: return null
        return inferFromVisibleSymbol(visibleInitializer, context, visited)
    }

    private fun inferImportExpressionType(expression: com.intellij.psi.PsiElement): XMakeType? {
        val functionCall = when (expression) {
            is LuaFunctionCall -> expression
            else -> PsiTreeUtil.findChildOfType(expression, LuaFunctionCall::class.java)
        } ?: return null
        if (!ImportCallParser.isImportCall(functionCall)) {
            return null
        }
        val importSpec = ImportCallParser.parse(functionCall).getOrNull() ?: return null
        return XMakeType.Module(importSpec.modulePath, ApiLookupView.SCRIPT_GLOBAL_ROOT)
    }

    private fun inferChainedExpressionType(
        expression: com.intellij.psi.PsiElement,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        val targetIdentifier = PsiTreeUtil.findChildrenOfType(expression, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { PsiPredicates.isFunctionCall(it) }
            .sortedBy { it.textOffset }
            .lastOrNull()
            ?: return null

        return inferCallChainTargetType(targetIdentifier, context, visited, typeResolver)
    }

    private fun inferCallChainTargetType(identifier: XMakeLuaIdentifier): XMakeType? {
        return inferCallChainTargetType(identifier, defaultContext(identifier))
    }

    private fun inferCallChainTargetType(
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView
    ): XMakeType? {
        val typeResolver = safeTypeResolver(identifier) ?: return null

        return inferCallChainTargetType(identifier, context, mutableSetOf(), typeResolver)
    }

    private fun inferCallChainTargetType(
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        val chain = LuaCallChainResolver.resolve(identifier) ?: return null
        if (chain.targetIdentifier !== identifier) {
            return null
        }

        val functionCall = PsiTreeUtil.getParentOfType(identifier, LuaFunctionCall::class.java) ?: return null
        var currentType = inferChainBaseType(chain.identifiers.first(), context, visited, typeResolver) ?: return null
        for (index in 1 until chain.identifiers.size) {
            val current = chain.identifiers[index]
            val separator = chain.separators.getOrNull(index - 1)
                ?: LuaCallChainResolver.directMemberSeparator(chain.identifiers[index - 1], current, functionCall)
                ?: return null
            currentType = typeResolver.resolveMemberReturnType(currentType, current.text, separator) ?: return null
        }
        return currentType
    }

    private fun inferChainBaseType(
        identifier: XMakeLuaIdentifier,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        resolveIdentifierType(identifier, context, typeResolver)?.let { return it }

        val visible = VisibleSymbolResolver.resolve(identifier, context) ?: return null
        return inferFromVisibleSymbol(visible, context, visited)
    }

    private fun safeTypeResolver(element: PsiElement): TypeResolver? = try {
        element.project.getService(XMakeApi::class.java)?.typeResolver
    } catch (e: com.intellij.serviceContainer.AlreadyDisposedException) {
        LOG.debug("Type inference skipped (disposed): ${e.message}", e)
        null
    }

    private fun safeApi(element: PsiElement): XMakeApi? = try {
        element.project.getService(XMakeApi::class.java)
    } catch (e: com.intellij.serviceContainer.AlreadyDisposedException) {
        LOG.debug("Type inference skipped (disposed): ${e.message}", e)
        null
    }

    private fun findInitializerExpression(identifier: XMakeLuaIdentifier): com.intellij.psi.PsiElement? {
        val statement = PsiTreeUtil.getParentOfType(identifier, LuaStatement::class.java) ?: return null
        val eqLeaf = LuaPsiVisibleLeaves.findWithin(statement, "=") ?: return null

        val variableList = PsiTreeUtil.findChildOfType(statement, LuaVariableList::class.java)
        val expressionList = PsiTreeUtil.findChildOfType(statement, LuaExpressionList::class.java)

        if (variableList == null || expressionList == null) {
            return findInitializerByOffsets(identifier, statement, eqLeaf.textRange.startOffset)
        }

        val variables = PsiTreeUtil.findChildrenOfType(variableList, XMakeLuaIdentifier::class.java).toList()
        val variableIndex = variables.indexOf(identifier)
        if (variableIndex < 0) return null

        return expressionList.getExpressions().getOrNull(variableIndex)
    }

    private fun findInitializerByOffsets(
        identifier: XMakeLuaIdentifier,
        statement: LuaStatement,
        eqOffset: Int
    ): com.intellij.psi.PsiElement? {
        val identifiers = PsiTreeUtil.findChildrenOfType(statement, XMakeLuaIdentifier::class.java)
            .asSequence()
            .sortedBy { it.textOffset }
            .toList()
        val lhsIdentifiers = identifiers.filter { it.textOffset < eqOffset }
        val variableIndex = lhsIdentifiers.indexOf(identifier)
        if (variableIndex < 0) return null

        val rhsIdentifiers = identifiers.filter { it.textOffset > eqOffset }
        val rhsIdentifier = rhsIdentifiers.getOrNull(variableIndex) ?: return null
        return PsiTreeUtil.getParentOfType(rhsIdentifier, LuaExpression::class.java, false, LuaStatement::class.java)
            ?: rhsIdentifier
    }

    private fun findReturnedExpression(identifier: XMakeLuaIdentifier): com.intellij.psi.PsiElement? {
        val functionDefinition = PsiTreeUtil.getParentOfType(identifier, LuaFunctionDefinition::class.java) ?: return null
        val returnStatements = PsiTreeUtil.findChildrenOfType(functionDefinition, LuaReturnStatement::class.java)
            .asSequence()
            .filter { PsiTreeUtil.getParentOfType(it, LuaFunctionDefinition::class.java) == functionDefinition }
            .sortedBy { it.textOffset }
            .toList()

        val returnStatement = returnStatements.firstOrNull() ?: return null
        val expressionList = PsiTreeUtil.findChildOfType(returnStatement, LuaExpressionList::class.java)
        if (expressionList != null) {
            return expressionList.getExpressions().firstOrNull()
        }

        val returnedIdentifier = PsiTreeUtil.findChildOfType(returnStatement, XMakeLuaIdentifier::class.java) ?: return null
        return PsiTreeUtil.getParentOfType(returnedIdentifier, LuaExpression::class.java, false, LuaReturnStatement::class.java)
            ?: returnedIdentifier
    }

    // Use the shared API context boundary so structural entry calls are typed
    // from the parent description context they are declared in.
    private fun defaultContext(identifier: XMakeLuaIdentifier): ApiLookupView =
        ApiLookupContext.forIdentifier(identifier)
}
