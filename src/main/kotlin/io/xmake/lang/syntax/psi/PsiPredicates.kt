package io.xmake.lang.syntax.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.lua.LuaChunk
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaFunctionName
import io.xmake.lang.syntax.psi.lua.LuaFunctionDefinition
import io.xmake.lang.syntax.psi.lua.LuaFunctionBody
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaParameterList
import io.xmake.lang.syntax.psi.lua.LuaStatement
import io.xmake.lang.syntax.psi.lua.LuaTableConstructor
import io.xmake.lang.syntax.psi.lua.LuaVariableDefinition

object PsiPredicates {
    private data class ForLoopContext(
        val statement: LuaStatement,
        val loopBody: LuaBlock
    )

    private data class LocalFunctionDeclarationTokens(
        val localKeyword: PsiElement,
        val functionKeyword: PsiElement
    )

    private val unresolvedVariableExclusions = arrayOf<(XMakeLuaIdentifier) -> Boolean>(
        ::isFunctionDefinition,
        ::isFunctionDeclarationName,
        ::isAssignmentTarget,
        ::isLocalVariable,
        ::isParameter,
        ::isLabel,
        ::isGotoLabelReference,
        ::isTableKey,
        ::isTableFieldAccess,
        ::isFunctionCall
    )

    fun isFunctionDefinition(element: XMakeLuaIdentifier): Boolean =
        element.parent is LuaFunctionDefinition

    fun isFunctionDeclarationName(element: XMakeLuaIdentifier): Boolean {
        val parent = element.parent
        if (parent is LuaFunctionName) {
            val names = PsiTreeUtil.findChildrenOfType(parent, XMakeLuaIdentifier::class.java)
            return names.lastOrNull() == element
        }

        return isLocalFunctionDeclarationName(element)
    }

    fun isBindableFunctionDeclarationName(element: XMakeLuaIdentifier): Boolean {
        val parent = element.parent
        if (parent is LuaFunctionName) {
            val names = PsiTreeUtil.findChildrenOfType(parent, XMakeLuaIdentifier::class.java)
            return names.singleOrNull() == element
        }

        return isLocalFunctionDeclarationName(element)
    }

    private fun isLocalFunctionDeclarationName(element: XMakeLuaIdentifier): Boolean {
        val statement = PsiTreeUtil.getParentOfType(element, LuaStatement::class.java) ?: return false
        val declarationTokens = localFunctionDeclarationTokens(statement) ?: return false
        val functionKeyword = declarationTokens.functionKeyword
        if (LuaPsiVisibleLeaves.previousWithin(element, statement) != functionKeyword) return false
        return LuaPsiVisibleLeaves.nextWithin(element, statement)?.text == "("
    }

    fun isFunctionCall(element: XMakeLuaIdentifier): Boolean =
        element.parent is LuaFunctionCall

    fun isLocalVariable(element: XMakeLuaIdentifier): Boolean {
        if (isForLoopVariable(element)) return true

        val statement = PsiTreeUtil.getParentOfType(element, LuaStatement::class.java) ?: return false
        val firstToken = LuaPsiVisibleLeaves.firstWithin(statement) ?: return false
        if (firstToken.text != "local") return false

        val secondToken = LuaPsiVisibleLeaves.nextWithin(firstToken, statement) ?: return false
        if (secondToken.text == "function") return false

        for (token in visibleLeafSequence(statement, firstToken)) {
            if (token == element) return true
            if (token.text == "=") return false
        }
        return false
    }

    fun isForLoopVariable(element: XMakeLuaIdentifier): Boolean {
        val context = forLoopContext(element) ?: return false
        if (element.textOffset >= context.loopBody.textRange.startOffset) return false

        for (token in previousVisibleLeavesWithin(element, context.statement)) {
            when (token.text) {
                "for" -> return true
                "in", "=" -> return false
            }
        }
        return false
    }

    fun declarationScope(element: XMakeLuaIdentifier): PsiElement? {
        if (isParameter(element)) {
            val functionDefinition = PsiTreeUtil.getParentOfType(element, LuaFunctionDefinition::class.java) ?: return null
            return PsiTreeUtil.findChildOfType(functionDefinition, LuaFunctionBody::class.java) ?: functionDefinition
        }
        if (isForLoopVariable(element)) {
            return forLoopContext(element)?.loopBody
        }
        return PsiTreeUtil.getParentOfType(element, LuaBlock::class.java, LuaChunk::class.java)
    }

    fun isAssignmentTarget(element: XMakeLuaIdentifier): Boolean =
        (element.parent as? LuaVariableDefinition)?.text == element.text

    fun isParameter(element: XMakeLuaIdentifier): Boolean {
        return generateSequence(element.parent) { it.parent }
            .takeWhile { it !is LuaFunctionDefinition && it !is LuaChunk }
            .any { it is LuaParameterList }
    }

    fun isLabel(element: XMakeLuaIdentifier): Boolean {
        val prevLeaf = PsiTreeUtil.prevLeaf(element)
        val nextLeaf = PsiTreeUtil.nextLeaf(element)
        return prevLeaf?.text?.trim() == "::" && nextLeaf?.text?.trim() == "::"
    }

    fun isGotoLabelReference(element: XMakeLuaIdentifier): Boolean {
        val prevLeaf = PsiTreeUtil.prevVisibleLeaf(element)
        return prevLeaf?.text == "goto"
    }

    fun isTableKey(element: XMakeLuaIdentifier): Boolean {
        val tableConstructor = PsiTreeUtil.getParentOfType(element, LuaTableConstructor::class.java)
            ?: return false
        if (LuaPsiVisibleLeaves.previousWithin(element, tableConstructor)?.text == ".") {
            return false
        }
        return LuaPsiVisibleLeaves.nextWithin(element, tableConstructor)?.text == "="
    }

    fun isTableFieldAccess(element: XMakeLuaIdentifier): Boolean =
        PsiTreeUtil.prevLeaf(element)?.text?.trim() == "."

    fun isUnresolvedVariableCandidate(element: XMakeLuaIdentifier): Boolean =
        unresolvedVariableExclusions.none { check -> check(element) }

    fun isDeclaration(element: XMakeLuaIdentifier): Boolean =
        isLocalVariable(element) ||
            isParameter(element) ||
            isBindableFunctionDeclarationName(element)

    private fun forLoopContext(element: XMakeLuaIdentifier): ForLoopContext? {
        val statement = PsiTreeUtil.getParentOfType(element, LuaStatement::class.java) ?: return null
        if (LuaPsiVisibleLeaves.firstWithin(statement)?.text != "for") return null
        val loopBody = PsiTreeUtil.getChildOfType(statement, LuaBlock::class.java) ?: return null
        return ForLoopContext(statement = statement, loopBody = loopBody)
    }

    private fun localFunctionDeclarationTokens(statement: LuaStatement): LocalFunctionDeclarationTokens? {
        val firstToken = LuaPsiVisibleLeaves.firstWithin(statement) ?: return null
        val secondToken = LuaPsiVisibleLeaves.nextWithin(firstToken, statement) ?: return null
        return LocalFunctionDeclarationTokens(
            localKeyword = firstToken,
            functionKeyword = secondToken
        ).takeIf { it.localKeyword.text == "local" && it.functionKeyword.text == "function" }
    }

    private fun visibleLeafSequence(scope: PsiElement, start: PsiElement?): Sequence<PsiElement> {
        if (start == null) {
            return emptySequence()
        }
        return generateSequence(start) { current -> LuaPsiVisibleLeaves.nextWithin(current, scope) }
    }

    private fun previousVisibleLeavesWithin(
        start: PsiElement,
        scope: PsiElement
    ): Sequence<PsiElement> = generateSequence(PsiTreeUtil.prevVisibleLeaf(start)) { current ->
        PsiTreeUtil.prevVisibleLeaf(current)
    }.takeWhile { token ->
        PsiTreeUtil.isAncestor(scope, token, false)
    }
}
