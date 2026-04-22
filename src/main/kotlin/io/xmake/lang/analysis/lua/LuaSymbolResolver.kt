package io.xmake.lang.analysis.lua

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbol
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbolProvider
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaChunk
import io.xmake.lang.syntax.psi.lua.LuaFunctionBody
import io.xmake.lang.syntax.psi.lua.LuaParameterList
import org.antlr.intellij.adaptor.lexer.TokenIElementType

object LuaSymbolResolver {
    private val LOG = logger<LuaSymbolResolver>()

    fun resolveLocal(element: XMakeLuaIdentifier): PsiElement? {
        if (PsiPredicates.isAssignmentTarget(element)) {
            findVisibleLexicalDeclaration(element, element.name, element.textOffset)?.let { return it }
            return element
        }

        if (PsiPredicates.isDeclaration(element)) {
            return element
        }

        val name = element.name
        findVisibleLexicalDeclaration(element, name, element.textOffset)?.let { return it }
        return findImplicitGlobalAssignment(element, name, element.textOffset)
    }

    fun resolveSynthetic(element: XMakeLuaIdentifier): XMakeSyntheticSymbol? {
        val file = element.containingFile as? XMakeLuaFile ?: return null
        val api = try {
            element.project.getService(XMakeApi::class.java)
        } catch (e: Exception) {
            LOG.debug("Symbol resolution failed: ${e.message}", e)
            null
        } ?: return null
        val context = ApiLookupContext.forIdentifier(element)
        return XMakeSyntheticSymbolProvider.resolve(
            api = api,
            file = file,
            place = element,
            name = element.name,
            context = context
        )
    }

    fun resolveVisibleElement(element: XMakeLuaIdentifier): PsiElement? {
        if (PsiPredicates.isGotoLabelReference(element)) {
            return resolveLabelReference(element)
        }
        if (PsiPredicates.isLabel(element)) {
            return element
        }
        resolveLocal(element)?.let { return it }
        return resolveSynthetic(element)?.declarationElement
    }

    fun visibleDeclarations(place: PsiElement, beforeOffset: Int = place.textOffset): List<XMakeLuaIdentifier> {
        val visible = mutableListOf<XMakeLuaIdentifier>()
        val seenNames = mutableSetOf<String>()

        fun record(declaration: XMakeLuaIdentifier) {
            val name = declaration.name
            if (seenNames.add(name)) {
                visible += declaration
            }
        }

        lexicalScopeChain(place).forEach { scope ->
            declarationsInScope(scope, beforeOffset).forEach(::record)
            enclosingFunctionBody(scope)?.let { functionBody ->
                parameterDeclarations(functionBody, beforeOffset).forEach(::record)
            }
        }
        implicitGlobalAssignments(place, beforeOffset).forEach(::record)

        return visible
    }

    private fun findVisibleLexicalDeclaration(
        place: PsiElement,
        name: String,
        beforeOffset: Int = place.textOffset
    ): XMakeLuaIdentifier? {
        val scopeChain = lexicalScopeChain(place)
        for (scope in scopeChain) {
            ProgressManager.checkCanceled()
            findDeclarationInScope(scope, name, beforeOffset)?.let { return it }
            enclosingFunctionBody(scope)?.let { functionBody ->
                findParameterDeclaration(functionBody, name, beforeOffset)?.let { return it }
            }
        }
        return null
    }

    fun findVisibleDeclaration(
        place: PsiElement,
        name: String,
        beforeOffset: Int = place.textOffset
    ): XMakeLuaIdentifier? {
        findVisibleLexicalDeclaration(place, name, beforeOffset)?.let { return it }
        return findImplicitGlobalAssignment(place, name, beforeOffset)
    }

    fun findVisibleSynthetic(place: PsiElement, name: String): XMakeSyntheticSymbol? {
        val file = place.containingFile as? XMakeLuaFile ?: return null
        val api = try {
            place.project.getService(XMakeApi::class.java)
        } catch (e: Exception) {
            LOG.debug("Symbol resolution failed: ${e.message}", e)
            null
        } ?: return null
        val context = ApiLookupContext.at(place)
        return XMakeSyntheticSymbolProvider.resolve(api, file, place, name, context)
    }

    private fun lexicalScopeChain(element: PsiElement): Sequence<PsiElement> = sequence {
        var current: PsiElement? = nearestLexicalScope(element)
        while (current != null) {
            yield(current)
            current = nearestLexicalScope(current.parent)
        }
    }

    private fun nearestLexicalScope(element: PsiElement?): PsiElement? {
        return PsiTreeUtil.getParentOfType(element, LuaBlock::class.java, LuaChunk::class.java)
    }

    fun resolveLabelReference(element: XMakeLuaIdentifier): XMakeLuaIdentifier? {
        if (!PsiPredicates.isGotoLabelReference(element)) {
            return null
        }

        val name = element.name
        val scopeChain = labelScopeChain(element)
        for (scope in scopeChain) {
            findLabelInScope(scope, name)?.let { return it }
        }
        return null
    }

    fun labelScopeChain(element: PsiElement): Sequence<PsiElement> = sequence {
        val root = labelVisibilityRoot(element) ?: return@sequence
        var current: PsiElement? = nearestLexicalScope(element)
        while (current != null) {
            yield(current)
            if (current == root) {
                break
            }
            current = nearestLexicalScope(current.parent)
        }
    }

    fun labelVisibilityRoot(element: PsiElement): PsiElement? {
        val functionBody = PsiTreeUtil.getParentOfType(element, LuaFunctionBody::class.java)
        if (functionBody != null) {
            return PsiTreeUtil.findChildOfType(functionBody, LuaBlock::class.java)
        }
        return PsiTreeUtil.getParentOfType(element, LuaChunk::class.java)
    }

    private fun declarationScope(element: XMakeLuaIdentifier): PsiElement? {
        return PsiPredicates.declarationScope(element)
    }

    private fun declarationSearchRoot(scope: PsiElement): PsiElement {
        val parentStatement =
            PsiTreeUtil.getParentOfType(scope, io.xmake.lang.syntax.psi.lua.LuaStatement::class.java, true)
        if (scope is LuaBlock && parentStatement != null && parentStatement.isForStatement()) {
            return parentStatement
        }
        return scope
    }

    private fun PsiElement.isForStatement(): Boolean {
        val firstLeaf = LuaPsiVisibleLeaves.firstWithin(this) ?: return false
        val tokenType = firstLeaf.node?.elementType as? TokenIElementType ?: return false
        return tokenType.antlrTokenType == LuaLexer.FOR
    }

    private fun findLabelInScope(scope: PsiElement, name: String): XMakeLuaIdentifier? {
        return PsiTreeUtil.findChildrenOfType(scope, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.name == name }
            .filter { PsiPredicates.isLabel(it) }
            .filter { nearestLexicalScope(it) == scope }
            .firstOrNull()
    }

    private fun findImplicitGlobalAssignment(place: PsiElement, name: String, beforeOffset: Int): XMakeLuaIdentifier? {
        val chunk = PsiTreeUtil.getParentOfType(place, LuaChunk::class.java) ?: return null
        val assignments = implicitGlobalAssignments(chunk, beforeOffset)
            .asSequence()
            .filter { it.name == name }

        return assignments.firstOrNull { assignment ->
            findVisibleLexicalDeclaration(assignment, name, assignment.textOffset) == null
        }
    }

    private fun findDeclarationInScope(scope: PsiElement, name: String, beforeOffset: Int): XMakeLuaIdentifier? {
        val declarations = PsiTreeUtil.findChildrenOfType(declarationSearchRoot(scope), XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.name == name }
            .filter { PsiPredicates.isDeclaration(it) }
            .filter { declarationScope(it) == scope }
            .filter { it.textOffset < beforeOffset }
            .sortedByDescending { it.textOffset }

        return declarations.firstOrNull()
    }

    private fun findParameterDeclaration(
        functionBody: LuaFunctionBody,
        name: String,
        placeOffset: Int
    ): XMakeLuaIdentifier? {
        return parameterDeclarations(functionBody, placeOffset)
            .asSequence()
            .filter { it.name == name }
            .firstOrNull()
    }

    private fun declarationsInScope(scope: PsiElement, beforeOffset: Int): List<XMakeLuaIdentifier> =
        PsiTreeUtil.findChildrenOfType(declarationSearchRoot(scope), XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textOffset < beforeOffset }
            .filter { PsiPredicates.isDeclaration(it) }
            .filter { declarationScope(it) == scope }
            .sortedByDescending { it.textOffset }
            .toList()

    private fun parameterDeclarations(functionBody: LuaFunctionBody, placeOffset: Int): List<XMakeLuaIdentifier> {
        val parameterList =
            PsiTreeUtil.findChildOfType(functionBody, LuaParameterList::class.java) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(parameterList, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textOffset <= placeOffset }
            .sortedByDescending { it.textOffset }
            .toList()
    }

    private fun implicitGlobalAssignments(place: PsiElement, beforeOffset: Int): List<XMakeLuaIdentifier> {
        val chunk = PsiTreeUtil.getParentOfType(place, LuaChunk::class.java) ?: return emptyList()
        return implicitGlobalAssignments(chunk, beforeOffset)
    }

    private fun implicitGlobalAssignments(chunk: LuaChunk, beforeOffset: Int): List<XMakeLuaIdentifier> =
        PsiTreeUtil.findChildrenOfType(chunk, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { PsiPredicates.isAssignmentTarget(it) }
            .filter { it.textOffset < beforeOffset }
            .sortedByDescending { it.textOffset }
            .toList()

    private fun enclosingFunctionBody(scope: PsiElement): LuaFunctionBody? {
        return PsiTreeUtil.getParentOfType(scope, LuaFunctionBody::class.java)
    }
}
