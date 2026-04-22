package io.xmake.lang.antlr;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;

public abstract class LuaParserBase extends Parser {
    protected LuaParserBase(TokenStream input) {
        super(input);
    }

    protected boolean IsFunctionCall() {
        BufferedTokenStream stream = (BufferedTokenStream) _input;
        var la = stream.LT(1);
        if (la.getType() != LuaLexer.NAME) return false;
        la = stream.LT(2);
        return la.getType() != LuaLexer.OP;
    }
}
