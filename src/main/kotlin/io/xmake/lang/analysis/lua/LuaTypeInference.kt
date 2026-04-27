package io.xmake.lang.analysis.lua

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.analysis.xmake.XMakeVerifiedHookParameterTypes
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.import.ImportBinding
import io.xmake.lang.declarations.import.ImportCallParser
import io.xmake.lang.declarations.import.ImportModuleExportsResolver
import io.xmake.lang.declarations.import.ImportModuleFileResolver
import io.xmake.lang.declarations.import.ImportedObjectKind
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.resolver.TypeResolver
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaAttributeNameList
import io.xmake.lang.syntax.psi.lua.LuaExpression
import io.xmake.lang.syntax.psi.lua.LuaExpressionList
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
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
        val visible = VisibleSymbolResolver.resolve(identifier, context)
        visible
            ?.inferredType
            ?.let { return it }
        if (visible is VisibleSymbol.Local) {
            return null
        }
        typeResolver.resolveType(identifier.text, context)?.let { return it }
        return null
    }

    private fun inferExpressionType(
        expression: PsiElement,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        inferImportExpressionType(expression)?.let { return it }
        directFunctionCallExpression(expression)
            ?.let { inferDirectCallExpressionType(it, context, visited, typeResolver) }
            ?.let { return it }

        val initializer = directIdentifierExpression(expression) ?: return null
        resolveIdentifierType(initializer, context, typeResolver)?.let { return it }

        val visibleInitializer = VisibleSymbolResolver.resolve(initializer, context) ?: return null
        return inferFromVisibleSymbol(visibleInitializer, context, visited)
    }

    private fun inferImportExpressionType(expression: PsiElement): XMakeType? {
        val functionCall = directFunctionCallExpression(expression) ?: return null
        if (!ImportCallParser.isImportCall(functionCall)) {
            return null
        }
        val importSpec = ImportCallParser.parse(functionCall).getOrNull() ?: return null
        if (importSpec.tryImport) {
            return null
        }
        val api = safeApi(functionCall) ?: return null
        val exports = ImportModuleExportsResolver(
            project = functionCall.project,
            lookup = api.lookup,
            scriptDirectory = ImportModuleFileResolver.scriptDirectoryOf(functionCall)
        ).resolve(
            ImportBinding(
                modulePath = importSpec.modulePath,
                rootDir = importSpec.rootDir,
                noLocal = importSpec.noLocal
            )
        ) ?: return null
        if (exports.hasUnknownMembers) {
            return XMakeType.Unknown
        }
        return when (exports.kind) {
            ImportedObjectKind.MODULE -> XMakeType.Module(exports.identifier, ApiLookupView.SCRIPT_GLOBAL_ROOT)
            ImportedObjectKind.CALLABLE -> XMakeType.Function()
            ImportedObjectKind.DIRECTORY,
            ImportedObjectKind.NATIVE_BINARY,
            ImportedObjectKind.NATIVE_SHARED -> XMakeType.Unknown
        }
    }

    private fun inferDirectCallExpressionType(
        functionCall: LuaFunctionCall,
        context: ApiLookupView,
        visited: MutableSet<XMakeLuaIdentifier>,
        typeResolver: TypeResolver
    ): XMakeType? {
        val calledIdentifier = directCallTargetIdentifier(functionCall) ?: return null
        inferCallChainTargetType(calledIdentifier, context, visited, typeResolver)?.let { return it }

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
        return null
    }

    private fun directFunctionCallExpression(expression: PsiElement): LuaFunctionCall? {
        return when (expression) {
            is LuaFunctionCall -> expression
            is LuaExpression -> PsiTreeUtil.findChildrenOfType(expression, LuaFunctionCall::class.java)
                .singleOrNull { call -> hasSameVisibleLeafRange(expression, call) }

            else -> null
        }
    }

    private fun directCallTargetIdentifier(functionCall: LuaFunctionCall): XMakeLuaIdentifier? {
        return PsiTreeUtil.findChildrenOfType(functionCall, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { PsiTreeUtil.getParentOfType(it, LuaFunctionCall::class.java) == functionCall }
            .sortedBy { it.textOffset }
            .lastOrNull()
    }

    private fun directIdentifierExpression(expression: PsiElement): XMakeLuaIdentifier? {
        if (expression is XMakeLuaIdentifier) {
            return expression
        }
        if (expression !is LuaExpression) {
            return null
        }
        if (PsiTreeUtil.findChildOfType(expression, LuaFunctionCall::class.java) != null) {
            return null
        }
        val identifiers = PsiTreeUtil.findChildrenOfType(expression, XMakeLuaIdentifier::class.java).toList()
        val identifier = identifiers.singleOrNull() ?: return null
        return identifier.takeIf { isOnlyVisibleLeaf(expression, it) }
    }

    private fun isOnlyVisibleLeaf(scope: PsiElement, leaf: PsiElement): Boolean {
        return LuaPsiVisibleLeaves.firstWithin(scope) == leaf &&
            LuaPsiVisibleLeaves.nextWithin(leaf, scope) == null
    }

    private fun hasSameVisibleLeafRange(outer: PsiElement, inner: PsiElement): Boolean {
        val outerFirst = LuaPsiVisibleLeaves.firstWithin(outer) ?: return false
        val innerFirst = LuaPsiVisibleLeaves.firstWithin(inner) ?: return false
        if (outerFirst != innerFirst) {
            return false
        }

        return lastVisibleLeafWithin(outerFirst, outer) == lastVisibleLeafWithin(innerFirst, inner)
    }

    private fun lastVisibleLeafWithin(first: PsiElement, scope: PsiElement): PsiElement {
        var last = first
        var current = LuaPsiVisibleLeaves.nextWithin(first, scope)
        while (current != null) {
            last = current
            current = LuaPsiVisibleLeaves.nextWithin(current, scope)
        }
        return last
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
        val visible = VisibleSymbolResolver.resolve(identifier, context)
        if (visible is VisibleSymbol.Local) {
            return inferFromVisibleSymbol(visible, context, visited)
        }

        resolveIdentifierType(identifier, context, typeResolver)?.let { return it }

        return visible?.let { inferFromVisibleSymbol(it, context, visited) }
    }

    private fun safeTypeResolver(element: PsiElement): TypeResolver? = try {
        element.project.getService(XMakeApi::class.java)?.typeResolver
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.debug("Type inference skipped: ${e.message}", e)
        null
    }

    private fun safeApi(element: PsiElement): XMakeApi? = try {
        element.project.getService(XMakeApi::class.java)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.debug("Type inference skipped: ${e.message}", e)
        null
    }

    private fun findInitializerExpression(identifier: XMakeLuaIdentifier): PsiElement? {
        val statement = PsiTreeUtil.getParentOfType(identifier, LuaStatement::class.java) ?: return null
        val eqLeaf = LuaPsiVisibleLeaves.findWithin(statement, "=") ?: return null

        val expressionList = PsiTreeUtil.getChildOfType(statement, LuaExpressionList::class.java)
        if (expressionList == null || expressionList.textRange.startOffset < eqLeaf.textRange.endOffset) return null

        val variables = findInitializerVariables(identifier, statement) ?: return null
        val variableIndex = variables.indexOf(identifier)
        if (variableIndex < 0) return null

        return expressionList.getExpressions().getOrNull(variableIndex)
    }

    private fun findInitializerVariables(
        identifier: XMakeLuaIdentifier,
        statement: LuaStatement
    ): List<XMakeLuaIdentifier>? {
        if (PsiPredicates.isLocalVariable(identifier)) {
            val attributeNameList = PsiTreeUtil.getChildOfType(statement, LuaAttributeNameList::class.java)
                ?: return null
            return PsiTreeUtil.findChildrenOfType(attributeNameList, XMakeLuaIdentifier::class.java)
                .filter { it.parent == attributeNameList }
        }

        val variableList = PsiTreeUtil.getChildOfType(statement, LuaVariableList::class.java)
            ?: return null
        return PsiTreeUtil.findChildrenOfType(variableList, XMakeLuaIdentifier::class.java).toList()
    }

    // Use the shared API context boundary so structural entry calls are typed
    // from the parent description context they are declared in.
    private fun defaultContext(identifier: XMakeLuaIdentifier): ApiLookupView =
        ApiLookupContext.forIdentifier(identifier)
}
