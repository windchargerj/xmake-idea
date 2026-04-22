package io.xmake.lang.syntax.parser

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.antlr.LuaParser
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.parser.ANTLRParserAdaptor
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.tree.ParseTree

class XMakeLuaParserAdaptor(parser: Parser) : ANTLRParserAdaptor(XMakeLuaLanguage.INSTANCE, parser) {

    override fun parse(parser: Parser, root: IElementType): ParseTree {
        val luaParser = parser as LuaParser

        luaParser.interpreter = XMakeLuaParserATNSimulator(
            luaParser,
            luaParser.atn,
            luaParser.interpreter.decisionToDFA,
            luaParser.interpreter.sharedContextCache
        )

        return when (root) {
            is IFileElementType -> luaParser.start_()
            is RuleIElementType -> {
                when (root.ruleIndex) {
                    LuaParser.RULE_functiondef -> luaParser.functiondef()
                    else -> luaParser.getInvokingContext(root.index.toInt())
                }
            }
            else -> luaParser.getInvokingContext(root.index.toInt())
        }
    }
}
