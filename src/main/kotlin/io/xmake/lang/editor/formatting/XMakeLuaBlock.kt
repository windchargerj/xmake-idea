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
        private val LUA_BLOCK_OPENING_TOKENS = tokenSet(
            LuaLexer.DO,
            LuaLexer.THEN,
            LuaLexer.ELSE,
            LuaLexer.REPEAT
        )
        private val LUA_FUNCTION_HEADER_END_TOKENS = tokenSet(LuaLexer.CP)
        private val LUA_BLOCK_CLOSING_TOKENS = tokenSet(LuaLexer.END, LuaLexer.UNTIL)
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
        formattingChildren()
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
            is LuaStatement -> statementIndent(currentPsi)
            else -> when {
                isCommentNode(myNode) -> commentIndent(currentPsi)
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
            is LuaStatement -> statementChildAttributes(currentPsi, newChildIndex)

            is LuaFunctionBody,
            is LuaTableConstructor -> ChildAttributes(enforcedNormalIndent(), null)

            is LuaBlock -> blockChildAttributes(currentPsi, newChildIndex)

            else -> ChildAttributes(Indent.getNoneIndent(), null)
        }
    }

    override fun isIncomplete(): Boolean {
        return when (val currentPsi = node.psi) {
            is LuaFunctionBody -> !myNode.hasMeaningfulToken(LUA_BLOCK_CLOSING_TOKENS)
            is LuaStatement -> opensLuaBlock(currentPsi) && !myNode.hasMeaningfulToken(LUA_BLOCK_CLOSING_TOKENS)
            else -> false
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
        val previousMeaningfulChild = formattingChildren()
            .take(newChildIndex)
            .lastOrNull(::isMeaningfulNode)

        val indentDepth = insertionIndentDepth(currentBlock, previousMeaningfulChild)
        return ChildAttributes(indentOf(indentDepth), null)
    }

    private fun statementChildAttributes(statement: LuaStatement, newChildIndex: Int): ChildAttributes {
        val previousMeaningfulChild = formattingChildren()
            .take(newChildIndex)
            .lastOrNull(::isMeaningfulNode)

        return when {
            previousMeaningfulChild?.isLuaBlockOpeningBoundary() == true ->
                ChildAttributes(enforcedNormalIndent(), null)

            opensLuaBlock(statement) || opensDescriptionStructure(statement) ->
                ChildAttributes(enforcedNormalIndent(), null)

            else -> ChildAttributes(Indent.getNoneIndent(), null)
        }
    }

    private fun statementIndent(statement: LuaStatement): Indent {
        val owner = statement.containingLuaBlockOwner()
        return when {
            owner is LuaChunk -> indentOf(rootIndentDepthForStatement(statement))
            owner is LuaFunctionBody || owner is LuaStatement -> Indent.getNormalIndent()
            isIndentedDescriptionStatement(statement) -> Indent.getNormalIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun commentIndent(comment: PsiElement): Indent {
        val owner = comment.containingLuaBlockOwner()
        return when {
            owner is LuaChunk -> indentOf(scopeIndentDepthFor(comment))
            owner is LuaFunctionBody || owner is LuaStatement -> Indent.getNormalIndent()
            else -> Indent.getNoneIndent()
        }
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

    private fun formattingChildren(): List<ASTNode> =
        generateSequence(myNode.firstChildNode) { it.treeNext }
            .flatMap(::formattingNodes)
            .toList()

    private fun formattingNodes(node: ASTNode): Sequence<ASTNode> {
        if (isWhitespaceNode(node)) {
            return emptySequence()
        }

        if (isTransparentLuaBlockNode(node)) {
            return generateSequence(node.firstChildNode) { it.treeNext }
                .flatMap(::formattingNodes)
        }

        return sequenceOf(node)
    }

    private fun insertionIndentDepth(currentBlock: LuaBlock, previousMeaningfulChild: ASTNode?): Int {
        if (previousMeaningfulChild == null) {
            return 0
        }

        val file = currentBlock.containingFile
        val insertionOffset = previousMeaningfulChild.textRange.endOffset
        if (previousMeaningfulChild.psi is LuaStatement) {
            return postStatementIndentDepth(previousMeaningfulChild.psi as LuaStatement)
        }

        if (insertionOffset < file.textLength) {
            return scopeIndentDepthFor(XMakeScopeQuery.model(currentBlock).stateAt(insertionOffset))
        }

        return scopeIndentDepthFor(previousMeaningfulChild.psi)
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
            opensLuaBlock(statement) -> depth + 1
            else -> depth
        }
    }

    private fun opensLuaBlock(statement: LuaStatement): Boolean {
        if (statement.node.hasMeaningfulToken(LUA_BLOCK_OPENING_TOKENS) &&
            !statement.node.hasMeaningfulToken(LUA_BLOCK_CLOSING_TOKENS)
        ) {
            return true
        }

        val lastLeaf = statement.node.lastMeaningfulLeaf() ?: return false
        if (LUA_BLOCK_OPENING_TOKENS.contains(lastLeaf.elementType)) {
            return true
        }
        return LUA_FUNCTION_HEADER_END_TOKENS.contains(lastLeaf.elementType) && statement.children.any { it is LuaFunctionBody }
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

    private fun enforcedNormalIndent(): Indent =
        Indent.getIndent(Indent.Type.NORMAL, false, true)

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

    private fun isWhitespaceNode(node: ASTNode): Boolean =
        node.elementType == TokenType.WHITE_SPACE

    private fun isCommentNode(node: ASTNode): Boolean =
        COMMENT_TOKENS.contains(node.elementType)

    private fun isMeaningfulNode(node: ASTNode): Boolean =
        !isWhitespaceNode(node) && !isCommentNode(node) && !isEmptyLuaBlockNode(node)

    private fun isEmptyLuaBlockNode(node: ASTNode): Boolean =
        node.psi is LuaBlock &&
            generateSequence(node.firstChildNode) { it.treeNext }
                .none { !isWhitespaceNode(it) && !isCommentNode(it) }

    private fun isTransparentLuaBlockNode(node: ASTNode): Boolean =
        node.psi is LuaBlock && node.treeParent?.psi !is LuaChunk

    private fun PsiElement.containingLuaBlockOwner(): PsiElement? {
        val block = parent as? LuaBlock ?: return null
        return block.parent
    }

    private fun ASTNode.isLuaBlockOpeningBoundary(): Boolean =
        LUA_BLOCK_OPENING_TOKENS.contains(elementType) ||
            (LUA_FUNCTION_HEADER_END_TOKENS.contains(elementType) && treeParent?.psi is LuaFunctionBody)

    private fun ASTNode.lastMeaningfulLeaf(): ASTNode? {
        var child = lastChildNode
        while (child != null) {
            child.lastMeaningfulLeaf()?.let { return it }
            child = child.treePrev
        }
        return takeIf(::isMeaningfulNode)
    }

    private fun ASTNode.hasMeaningfulToken(tokens: TokenSet): Boolean {
        var child = firstChildNode
        while (child != null) {
            if (child.hasMeaningfulToken(tokens)) {
                return true
            }
            child = child.treeNext
        }
        return isMeaningfulNode(this) && tokens.contains(elementType)
    }

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
