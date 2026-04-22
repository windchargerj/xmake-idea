package io.xmake.lang.highlight

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType
import io.xmake.lang.XMakeLuaLanguage
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.antlr.LuaLexer.*
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.TokenIElementType

class XMakeLuaSyntaxHighlighter: SyntaxHighlighter {

    override fun getHighlightingLexer(): Lexer {
        val lexer = LuaLexer(null)
        return ANTLRLexerAdaptor(XMakeLuaLanguage, lexer)
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when((tokenType as? TokenIElementType)?.antlrTokenType) {
            COMMENT -> arrayOf(XMakeLuaTextAttribute.COMMENT)
            in LUA_STRING -> arrayOf(XMakeLuaTextAttribute.STRING)
            in LUA_NUMBER -> arrayOf(XMakeLuaTextAttribute.NUMBER)
            in LUA_KEYWORD -> arrayOf(XMakeLuaTextAttribute.KEYWORD)
            else -> emptyArray()
        }
    }

    companion object {
        private val LUA_KEYWORD = arrayOf(
            AND, NOT, OR, IN,
            TRUE, FALSE, NIL,
            IF, THEN, ELSE, ELSEIF,
            DO, WHILE, UNTIL, BREAK,
            FOR, REPEAT, END, GOTO,
            FUNCTION, RETURN, LOCAL
        )
        private val LUA_STRING = arrayOf(NORMALSTRING, CHARSTRING, LONGSTRING)

        private val LUA_NUMBER = arrayOf(INT, HEX, FLOAT, HEX_FLOAT)
    }
}
