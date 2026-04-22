package io.xmake.lang

import com.intellij.lang.DefaultASTFactoryImpl
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.psi.XMakeLuaIdentifier
import org.antlr.intellij.adaptor.lexer.TokenIElementType

class XMakeLuaASTFactory : DefaultASTFactoryImpl() {
    override fun createComposite(type: IElementType): CompositeElement {
        return super.createComposite(type)
    }

    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement {
        if (type is TokenIElementType && type.antlrTokenType == LuaLexer.NAME) {
            return XMakeLuaIdentifier(type, text)
        }
        val leaf = super.createLeaf(type, text)
        return leaf
    }
}