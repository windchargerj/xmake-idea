package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

// e.g., `a, b` in `local a, b`
class LuaAttributeNameList(node: ASTNode) : ANTLRPsiNode(node)