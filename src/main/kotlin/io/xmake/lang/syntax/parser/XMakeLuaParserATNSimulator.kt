package io.xmake.lang.syntax.parser

import io.xmake.lang.antlr.LuaLexer
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.atn.ParserATNSimulator
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.dfa.DFA

class XMakeLuaParserATNSimulator(
    parser: Parser,
    atn: ATN,
    decisionToDFA: Array<DFA>,
    sharedContextCache: PredictionContextCache
) : ParserATNSimulator(parser, atn, decisionToDFA, sharedContextCache) {

    override fun adaptivePredict(
        input: TokenStream,
        decision: Int,
        outerContext: ParserRuleContext?
    ): Int {
        return try {
            super.adaptivePredict(input, decision, outerContext)
        } catch (e: Exception) {
            if (decision == PREFIXEXP_DECISION && isFunctionCallPattern(input)) {
                FUNCTIONCALL_ALT
            } else {
                throw e
            }
        }
    }

    private fun isFunctionCallPattern(input: TokenStream): Boolean {
        val la1 = input.LA(1)
        val la2 = input.LA(2)
        return la1 == LuaLexer.NAME && la2 == LuaLexer.OP
    }

    companion object {
        private const val PREFIXEXP_DECISION = 6
        private const val FUNCTIONCALL_ALT = 3
    }
}