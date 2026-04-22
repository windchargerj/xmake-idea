package io.xmake.lang.psi.lua

import com.intellij.lang.ASTNode
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

// e.g., `T.foo` or `T:bar`
class LuaFunctionName(node: ASTNode) : ANTLRPsiNode(node)