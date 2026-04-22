package io.xmake.lang.editor.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.antlr.LuaLexer.*
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.TokenIElementType

class XMakeLuaSyntaxHighlighter : SyntaxHighlighter {

    override fun getHighlightingLexer(): Lexer = ANTLRLexerAdaptor(XMakeLuaLanguage, LuaLexer(null))

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        if (tokenType == TokenType.BAD_CHARACTER || tokenType.isUnexpectedChar()) {
            return BAD_CHARACTER_KEYS
        }

        return when ((tokenType as? TokenIElementType)?.antlrTokenType) {
            in LUA_KEYWORD -> KEYWORD_KEYS
            in LUA_STRING -> STRING_KEYS
            in LUA_NUMBER -> NUMBER_KEYS
            COMMENT -> COMMENT_KEYS
            NAME -> IDENTIFIER_KEYS
            in LUA_OPERATORS -> OPERATION_SIGN_KEYS
            in LUA_BRACKETS -> BRACKETS_KEYS
            in LUA_PARENTHESES -> PARENTHESES_KEYS
            in LUA_SEPARATORS -> COMMA_KEYS
            DOT -> DOT_KEYS
            // Shebang
            SHEBANG -> COMMENT_KEYS
            else -> EMPTY_KEYS
        }
    }

    companion object {
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
        private val BAD_CHARACTER_KEYS = arrayOf(XMakeLuaTextAttribute.BAD_CHARACTER)
        private val KEYWORD_KEYS = arrayOf(XMakeLuaTextAttribute.KEYWORD)
        private val STRING_KEYS = arrayOf(XMakeLuaTextAttribute.STRING)
        private val NUMBER_KEYS = arrayOf(XMakeLuaTextAttribute.NUMBER)
        private val COMMENT_KEYS = arrayOf(XMakeLuaTextAttribute.COMMENT)
        private val IDENTIFIER_KEYS = arrayOf(XMakeLuaTextAttribute.IDENTIFIER)
        private val OPERATION_SIGN_KEYS = arrayOf(XMakeLuaTextAttribute.OPERATION_SIGN)
        private val BRACKETS_KEYS = arrayOf(XMakeLuaTextAttribute.BRACKETS)
        private val PARENTHESES_KEYS = arrayOf(XMakeLuaTextAttribute.PARENTHESES)
        private val COMMA_KEYS = arrayOf(XMakeLuaTextAttribute.COMMA)
        private val DOT_KEYS = arrayOf(XMakeLuaTextAttribute.DOT)

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
        private val LUA_OPERATORS = arrayOf(
            EQ, PLUS, MINUS, STAR, SLASH, PER, CARET,
            EE, SQEQ, LE, GE, LT, GT,
            AMP, PIPE, SS, LL, GG,
            NOT, AND, OR,
            DDD, DD, COL, SQUIG, CC, POUND
        )
        private val LUA_BRACKETS = arrayOf(OCU, CCU, OB, CB)
        private val LUA_PARENTHESES = arrayOf(OP, CP)
        private val LUA_SEPARATORS = arrayOf(COMMA, SEMI)
    }
}

private fun IElementType?.isUnexpectedChar(): Boolean =
    (this as? TokenIElementType)?.antlrTokenType == UNEXPECTED_CHAR
