package io.xmake.lang.syntax.psi.lua

import com.intellij.lang.ASTNode
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

// e.g., `a, b` in `a, b = 1, 2`
class LuaVariableList(node: ASTNode) : ANTLRPsiNode(node)