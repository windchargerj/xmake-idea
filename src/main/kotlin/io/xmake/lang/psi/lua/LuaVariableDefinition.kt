package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.adaptor.psi.IdentifierDefSubtree

open class LuaVariableDefinition(node: ASTNode, idElementTyp: IElementType) :
    IdentifierDefSubtree(node, idElementTyp)