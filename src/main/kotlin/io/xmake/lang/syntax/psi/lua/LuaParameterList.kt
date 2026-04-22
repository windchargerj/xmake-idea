package io.xmake.lang.syntax.psi.lua

import com.intellij.lang.ASTNode
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

// e.g., `(a, b)`
class LuaParameterList(node: ASTNode) : ANTLRPsiNode(node)