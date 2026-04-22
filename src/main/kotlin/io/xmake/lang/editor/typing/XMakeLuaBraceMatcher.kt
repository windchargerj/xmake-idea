package io.xmake.lang.editor.typing

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.syntax.XMakeLuaLanguage
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeLuaBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(
        lbraceType: IElementType,
        contextType: IElementType?
    ): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int =
        openingBraceOffset

    companion object {
        private val TOKEN_TYPES = PSIElementTypeFactory.getTokenIElementTypes(XMakeLuaLanguage.INSTANCE)

        private fun tokenType(token: Int): IElementType = TOKEN_TYPES[token]

        private val PAIRS = arrayOf(
            BracePair(tokenType(LuaLexer.OP), tokenType(LuaLexer.CP), false),
            BracePair(tokenType(LuaLexer.OB), tokenType(LuaLexer.CB), false),
            BracePair(tokenType(LuaLexer.OCU), tokenType(LuaLexer.CCU), true),
        )
    }
}
