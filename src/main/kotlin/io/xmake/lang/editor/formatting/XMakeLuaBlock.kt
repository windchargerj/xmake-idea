package io.xmake.lang.editor.formatting

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.firstLeaf
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.XMakeState
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.lua.*
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeLuaBlock(
    node: ASTNode,
    wrap: Wrap?,
    alignment: Alignment?,
    private val commonSettings: CommonCodeStyleSettings,
    private val spacingBuilder: SpacingBuilder
) : AbstractBlock(node, wrap, alignment) {

    companion object {
        private val NO_WRAP = Wrap.createWrap(WrapType.NONE, false)

        private fun tokenSet(vararg tokens: Int): TokenSet =
            PSIElementTypeFactory.createTokenSet(XMakeLuaLanguage.INSTANCE, *tokens)

        private val COMMENT_TOKENS = tokenSet(LuaLexer.COMMENT, LuaLexer.SHEBANG)
        private val CONTEXTUAL_ADDITIVE_OPERATORS = tokenSet(LuaLexer.PLUS, LuaLexer.MINUS)
        private val CONTEXTUAL_BITWISE_OPERATOR = tokenSet(LuaLexer.SQUIG)
        private val UNARY_OPERATOR_PREFIXES = setOf(
            "(",
            "{",
            "[",
            ",",
            "=",
            "+",
            "-",
            "*",
            "/",
            "%",
            "^",
            "..",
            "~",
            "&",
            "|",
            "<<",
            ">>",
            "and",
            "or",
            "not",
            "return"
        )
    }

    val luaNode: ASTNode
        get() = myNode

    override fun buildChildren(): List<Block> =
        nonWhitespaceChildren()
            .map { createChildBlock(it) }
            .toList()

    private fun createChildBlock(child: ASTNode): Block =
        XMakeLuaBlock(child, NO_WRAP, null, commonSettings, spacingBuilder)

    override fun getIndent(): Indent {
        val parent = myNode.treeParent ?: return Indent.getNoneIndent()
        val parentPsi = parent.psi

        return when (val currentPsi = node.psi) {
            is LuaBlock if parentPsi is LuaFunctionBody -> Indent.getNormalIndent()
            is LuaFieldList if parentPsi is LuaTableConstructor -> Indent.getNormalIndent()
            is LuaBlock if parentPsi is LuaStatement -> Indent.getNormalIndent()
            is LuaBlock if parentPsi is LuaChunk -> Indent.getNoneIndent()
            is LuaStatement if isRootLevelChild(parentPsi) -> indentOf(rootIndentDepthForStatement(currentPsi))
            is LuaStatement if isIndentedDescriptionStatement(currentPsi) -> Indent.getNormalIndent()
            else -> when {
                isRootLevelChild(parentPsi) && isCommentNode(myNode) -> indentOf(scopeIndentDepthFor(currentPsi))
                else -> Indent.getNoneIndent()
            }
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        customSpacing(child1, child2) ?: spacingBuilder.getSpacing(this, child1, child2)

    override fun isLeaf(): Boolean =
        myNode.firstChildNode == null

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        return when (val currentPsi = node.psi) {
            is LuaStatement if opensDescriptionStructure(currentPsi) ->
                ChildAttributes(Indent.getNormalIndent(), null)

            is LuaFunctionBody,
            is LuaTableConstructor -> ChildAttributes(Indent.getNormalIndent(), null)

            is LuaBlock -> blockChildAttributes(currentPsi, newChildIndex)

            else -> ChildAttributes(Indent.getNoneIndent(), null)
        }
    }

    private fun blockChildAttributes(currentBlock: LuaBlock, newChildIndex: Int): ChildAttributes {
        val parentPsi = currentBlock.parent
        return when {
            parentPsi is LuaChunk -> rootBlockChildAttributes(currentBlock, newChildIndex)
            else -> ChildAttributes(Indent.getNormalIndent(), null)
        }
    }

    private fun rootBlockChildAttributes(currentBlock: LuaBlock, newChildIndex: Int): ChildAttributes {
        val previousMeaningfulChild = nonWhitespaceChildren()
            .take(newChildIndex)
            .lastOrNull(::isMeaningfulNode)

        val indentDepth = insertionIndentDepth(currentBlock, previousMeaningfulChild)
        return ChildAttributes(indentOf(indentDepth), null)
    }

    private fun isIndentedDescriptionStatement(statement: LuaStatement): Boolean {
        if (XMakeDescriptionDomainRules.isStructuralKeyword(statement.firstLeaf().text)) {
            return false
        }
        return XMakeScopeQuery.stateAt(statement).domain is XMakeDomain.Configuration
    }

    private fun opensDescriptionStructure(statement: LuaStatement): Boolean =
        XMakeDescriptionDomainRules.isStructuralEntry(statement.firstLeaf().text)

    private fun closesDescriptionStructure(statement: LuaStatement): Boolean =
        XMakeDescriptionDomainRules.isStructuralEnd(statement.firstLeaf().text)

    private fun isSelfClosingDescriptionStatement(statement: LuaStatement): Boolean {
        if (!opensDescriptionStructure(statement)) {
            return false
        }
        val functionCall = statement.children.filterIsInstance<LuaFunctionCall>().firstOrNull() ?: return false
        return functionCall.hasFunctionBodyArgument
    }

    private fun nonWhitespaceChildren(): List<ASTNode> =
        generateSequence(myNode.firstChildNode) { it.treeNext }
            .filterNot(::isWhitespaceNode)
            .toList()

    private fun insertionIndentDepth(currentBlock: LuaBlock, previousMeaningfulChild: ASTNode?): Int {
        if (previousMeaningfulChild == null) {
            return 0
        }

        val file = currentBlock.containingFile
        val insertionOffset = previousMeaningfulChild.textRange.endOffset
        if (insertionOffset < file.textLength) {
            return scopeIndentDepthFor(XMakeScopeQuery.model(currentBlock).stateAt(insertionOffset))
        }

        return when (val previousPsi = previousMeaningfulChild.psi) {
            is LuaStatement -> postStatementIndentDepth(previousPsi)
            else -> scopeIndentDepthFor(previousPsi)
        }
    }

    private fun rootIndentDepthForStatement(statement: LuaStatement): Int {
        val depth = scopeIndentDepthFor(statement)
        return when {
            opensDescriptionStructure(statement) || closesDescriptionStructure(statement) -> (depth - 1).coerceAtLeast(0)
            else -> depth
        }
    }

    private fun postStatementIndentDepth(statement: LuaStatement): Int {
        val depth = scopeIndentDepthFor(statement)
        return when {
            closesDescriptionStructure(statement) -> (depth - 1).coerceAtLeast(0)
            opensDescriptionStructure(statement) && isSelfClosingDescriptionStatement(statement) -> (depth - 1).coerceAtLeast(0)
            else -> depth
        }
    }

    private fun indentOf(depth: Int): Indent {
        if (depth <= 0) return Indent.getNoneIndent()
        return when {
            commonSettings.indentOptions?.USE_TAB_CHARACTER == true ->
                if (depth == 1) Indent.getNormalIndent() else Indent.getContinuationIndent()
            else -> Indent.getSpaceIndent(depth * indentSize())
        }
    }

    private fun indentSize(): Int =
        commonSettings.indentOptions?.INDENT_SIZE ?: 4

    private fun scopeIndentDepthFor(element: PsiElement): Int =
        scopeIndentDepthFor(XMakeScopeQuery.stateAt(element))

    private fun scopeIndentDepthFor(state: XMakeState): Int =
        namespaceDepth(state.root) + when (state.domain) {
            is XMakeDomain.Configuration -> 1
            else -> 0
        }

    private fun namespaceDepth(root: XMakeRoot): Int =
        when (root) {
            is XMakeRoot.Global -> 0
            is XMakeRoot.Namespace -> 1 + namespaceDepth(root.parent)
        }

    private fun isRootLevelChild(parentPsi: PsiElement): Boolean =
        parentPsi is LuaBlock && parentPsi.parent is LuaChunk

    private fun isWhitespaceNode(node: ASTNode): Boolean =
        node.elementType == TokenType.WHITE_SPACE

    private fun isCommentNode(node: ASTNode): Boolean =
        COMMENT_TOKENS.contains(node.elementType)

    private fun isMeaningfulNode(node: ASTNode): Boolean =
        !isWhitespaceNode(node) && !isCommentNode(node)

    private fun customSpacing(child1: Block?, child2: Block): Spacing? {
        val leftNode = (child1 as? XMakeLuaBlock)?.luaNode
        val rightNode = (child2 as? XMakeLuaBlock)?.luaNode ?: return null

        spacingAroundContextualOperator(leftNode, rightNode)?.let { return it }
        spacingBeforeDelimitedBody(leftNode, rightNode)?.let { return it }

        return null
    }

    private fun spacingAroundContextualOperator(leftNode: ASTNode?, rightNode: ASTNode): Spacing? {
        val operatorNode = when {
            leftNode != null && isContextualOperator(leftNode) -> leftNode
            isContextualOperator(rightNode) -> rightNode
            else -> return null
        }

        val spaces = when {
            CONTEXTUAL_ADDITIVE_OPERATORS.contains(operatorNode.elementType) -> {
                if (operatorNode.isUnaryOperator()) 0 else spaces(commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS)
            }

            CONTEXTUAL_BITWISE_OPERATOR.contains(operatorNode.elementType) -> {
                if (operatorNode.isUnaryOperator()) 0 else spaces(commonSettings.SPACE_AROUND_BITWISE_OPERATORS)
            }

            else -> return null
        }

        return fixedSpacing(spaces)
    }

    private fun spacingBeforeDelimitedBody(leftNode: ASTNode?, rightNode: ASTNode): Spacing? {
        if (leftNode == null) {
            return null
        }

        val spaces = when (rightNode.psi) {
            is LuaArgs -> spaces(commonSettings.SPACE_BEFORE_METHOD_CALL_PARENTHESES)
            is LuaFunctionBody -> spaces(commonSettings.SPACE_BEFORE_METHOD_PARENTHESES)
            else -> return null
        }

        return fixedSpacing(spaces)
    }

    private fun isContextualOperator(node: ASTNode): Boolean =
        CONTEXTUAL_ADDITIVE_OPERATORS.contains(node.elementType) || CONTEXTUAL_BITWISE_OPERATOR.contains(node.elementType)

    private fun ASTNode.isUnaryOperator(): Boolean =
        previousSignificantSibling()?.text in UNARY_OPERATOR_PREFIXES || previousSignificantSibling() == null

    private fun ASTNode.previousSignificantSibling(): ASTNode? =
        generateSequence(treePrev) { it.treePrev }
            .firstOrNull(::isMeaningfulNode)

    private fun spaces(enabled: Boolean): Int = if (enabled) 1 else 0

    private fun fixedSpacing(spaces: Int): Spacing =
        Spacing.createSpacing(spaces, spaces, 0, commonSettings.KEEP_LINE_BREAKS, commonSettings.KEEP_BLANK_LINES_IN_CODE)
}
