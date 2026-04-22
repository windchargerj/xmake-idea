package io.xmake.lang.formatter

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.util.firstLeaf
import io.xmake.lang.psi.lua.*
import io.xmake.lang.psi.xmake.DomainScope

class XMakeLuaBlock(
    node: ASTNode,
    wrap: Wrap?,
    alignment: Alignment?,
    private val spacingBuilder: SpacingBuilder
) : AbstractBlock(node, wrap, alignment) {

    companion object {
        private val NO_WRAP = Wrap.createWrap(WrapType.NONE, false)
    }

    override fun buildChildren(): List<Block> =
        generateSequence(myNode.firstChildNode) { it.treeNext }
            .filter { it.elementType != TokenType.WHITE_SPACE }
            .map { createChildBlock(it) }
            .toList()

    private fun createChildBlock(child: ASTNode): Block =
        XMakeLuaBlock(child, NO_WRAP, null, spacingBuilder)

    override fun getIndent(): Indent {
        val parent = myNode.treeParent ?: return Indent.getNoneIndent()
        val parentPsi = parent.psi

        return when (val currentPsi = node.psi) {
            is LuaStatement if parentPsi is DomainScope
                    && currentPsi.firstLeaf().text !in DomainScope.DomainType.symbols
                -> Indent.getNormalIndent()

            is LuaBlock if parentPsi is LuaFunctionBody -> Indent.getNormalIndent()

            is LuaFieldList if
            parentPsi is LuaTableConstructor -> Indent.getNormalIndent()

            is LuaBlock if parentPsi is LuaStatement -> Indent.getNormalIndent()

            is LuaBlock if parentPsi is LuaChunk -> Indent.getNoneIndent()

            else -> Indent.getNoneIndent()
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        spacingBuilder.getSpacing(this, child1, child2)

    override fun isLeaf(): Boolean =
        myNode.firstChildNode == null

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        return when (val currentPsi = node.psi) {
            is LuaFunctionBody,
            is LuaTableConstructor,
            is DomainScope -> ChildAttributes(Indent.getNormalIndent(), null)

            is LuaBlock if currentPsi.parent !is LuaChunk -> ChildAttributes(Indent.getNormalIndent(), null)
            else -> ChildAttributes(Indent.getNoneIndent(), null)
        }
    }
}