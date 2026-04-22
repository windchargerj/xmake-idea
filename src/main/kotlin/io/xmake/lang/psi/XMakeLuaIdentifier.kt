package io.xmake.lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import io.xmake.lang.ID
import io.xmake.lang.XMakeLuaLanguage
import io.xmake.lang.antlr.LuaParser
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.psi.ANTLRPsiLeafNode
import org.antlr.intellij.adaptor.psi.Trees

class XMakeLuaIdentifier(type: IElementType?, text: CharSequence?) : ANTLRPsiLeafNode(type, text),
    PsiNamedElement {

    override fun getName(): String {
        return text
    }

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {

        val elType: IElementType = parent.node.elementType
        var kind = "??? "
        if (elType is RuleIElementType) {
            val ruleIndex: Int = (elType).ruleIndex
            if (ruleIndex == LuaParser.RULE_functioncall) {
                kind = "call "
            } else if (ruleIndex == LuaParser.RULE_stat) {
                kind = "assign "
            } else if (ruleIndex == LuaParser.RULE_functiondef) {
                kind = "func def "
            }
        }
        println(
            "XMakeLuaIdentifier.setName(" + name + ") on " +
                    kind + this + " at " + Integer.toHexString(this.hashCode())
        )

        val newID = Trees.createLeafFromText(
            project,
            XMakeLuaLanguage,
            context,
            name,
            ID
        )
        if (newID != null) {
            return this.replace(newID) // use replace on leaves but replaceChild on ID nodes that are part of defs/decls.
        }
        return this
    }

    override fun getReference(): PsiReference? {
        return null
    }
}
