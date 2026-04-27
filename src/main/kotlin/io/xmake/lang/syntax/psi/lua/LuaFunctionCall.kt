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

    val firstStaticStringArgument: String?
        get() = arguments.firstOrNull()?.asStaticStringLiteral()

    val hasFunctionBodyArgument: Boolean
        get() = arguments.any { argument ->
            PsiTreeUtil.findChildOfType(argument, LuaFunctionBody::class.java) != null
        }

    fun argumentKind(index: Int): ArgumentKind? =
        arguments.getOrNull(index)?.toArgumentKind()

    enum class ArgumentKind {
        STRING,
        TABLE,
        FUNCTION,
        DYNAMIC
    }

    private fun PsiElement.toArgumentKind(): ArgumentKind =
        when {
            asStaticStringLiteral() != null -> ArgumentKind.STRING
            this is LuaTableConstructor -> ArgumentKind.TABLE
            PsiTreeUtil.findChildOfType(this, LuaTableConstructor::class.java) != null -> ArgumentKind.TABLE
            this is LuaFunctionBody -> ArgumentKind.FUNCTION
            PsiTreeUtil.findChildOfType(this, LuaFunctionBody::class.java) != null -> ArgumentKind.FUNCTION
            else -> ArgumentKind.DYNAMIC
        }

    private fun PsiElement.asStaticStringLiteral(): String? {
        val string = when (this) {
            is LuaString -> this
            is LuaExpression -> PsiTreeUtil.findChildOfType(this, LuaString::class.java)
                ?.takeIf { text.trim() == it.text }

            else -> null
        } ?: return null

        val raw = string.text
        return when {
            raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") -> raw.substring(1, raw.length - 1)
            raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1)
            else -> raw
        }
    }
}
