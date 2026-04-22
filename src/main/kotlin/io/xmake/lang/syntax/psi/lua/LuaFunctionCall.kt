package io.xmake.lang.syntax.psi.lua

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
class LuaFunctionCall(node: ASTNode) : ANTLRPsiNode(node) {

    val calleeIdentifier: XMakeLuaIdentifier?
        get() = PsiTreeUtil.findChildOfType(this, XMakeLuaIdentifier::class.java)

    val calleeName: String?
        get() = calleeIdentifier?.name ?: firstChild?.text

    val arguments: List<PsiElement>
        get() = PsiTreeUtil.findChildOfType(this, LuaArgs::class.java)?.arguments.orEmpty()

    val firstStringArgument: String?
        get() = arguments.firstOrNull()?.text?.removeSurrounding("\"")?.removeSurrounding("'")

    val hasFunctionBodyArgument: Boolean
        get() = arguments.any { argument ->
            PsiTreeUtil.findChildOfType(argument, LuaFunctionBody::class.java) != null
        }
}
