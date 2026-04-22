package io.xmake.lang.antlr;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

public abstract class LuaLexerBase extends Lexer {

    private int start_line;
    private int start_col;

    protected LuaLexerBase(CharStream input) {
        super(input);
    }

    protected void HandleComment()
    {
        start_line = this.getLine();
        start_col = this.getCharPositionInLine() - 2;
        var cs = _input;
        if (cs.LA(1) == '[')
        {
            int sep = skip_sep(cs);
            if (sep >= 2)
            {
                read_long_string(cs, sep);
                return;
            }
        }
        while (cs.LA(1) != '\n' && cs.LA(1) != -1)
        {
            this.getInterpreter().consume(cs);
        }
    }

    private void read_long_string(CharStream cs, int sep)
    {
        boolean done = false;
        this.getInterpreter().consume(cs);
        do {
            var c = cs.LA(1);
            switch (c) {
                case -1:
                    done = true;
                    ANTLRErrorListener listener = this.getErrorListenerDispatch();
                    listener.syntaxError(this, null, this.start_line, this.start_col, "unfinished long comment", null);
                    break;
                case ']':
                    if (skip_sep(cs) == sep) {
                        this.getInterpreter().consume(cs);
                        done = true;
                    }
                    break;
                default:
                    if (cs.LA(1) == -1) {
                        done = true;
                        break;
                    }
                    this.getInterpreter().consume(cs);
                    break;
            }
        } while (!done);
    }

    private int skip_sep(CharStream cs)
    {
        int count = 0;
        int s = cs.LA(1);
        this.getInterpreter().consume(cs);
        while (cs.LA(1) == '=')
        {
            this.getInterpreter().consume(cs);
            count++;
        }
        if (cs.LA(1) == s) count += 2;
        else if (count == 0) count = 1;
        else count = 0;
        return count;
    }

    public boolean IsLine1Col0()
    {
        CharStream cs = _input;
        return cs.index() == 1;
    }

    public boolean IsLongStringStart() {
        CharStream cs = _input;
        int la1 = cs.LA(1);
        return la1 == '[' || la1 == '=';
    }

    protected void HandleLongString() {
        start_line = this.getLine();
        start_col = this.getCharPositionInLine() - 1;
        var cs = _input;
        int sep = skip_sep_for_string(cs);
        if (sep >= 2) {
            read_long_string_content(cs, sep);
        }
        // If sep < 2, it's just '[' which will be handled as OB token
        // We need to reset position - but since we already consumed,
        // this case means invalid long string, lexer will handle it
    }

    private int skip_sep_for_string(CharStream cs) {
        int count = 0;
        int s = cs.LA(1);
        if (s != '[' && s != '=') return 0;

        while (cs.LA(1) == '=') {
            this.getInterpreter().consume(cs);
            count++;
        }
        if (cs.LA(1) == '[') {
            this.getInterpreter().consume(cs);
            count += 2;
        } else {
            count = 0;
        }
        return count;
    }

    private void read_long_string_content(CharStream cs, int sep) {
        boolean done = false;
        do {
            var c = cs.LA(1);
            switch (c) {
                case -1:
                    done = true;
                    ANTLRErrorListener listener = this.getErrorListenerDispatch();
                    listener.syntaxError(this, null, this.start_line, this.start_col, "unfinished long string", null);
                    break;
                case ']':
                    if (skip_sep(cs) == sep) {
                        this.getInterpreter().consume(cs);
                        done = true;
                    }
                    break;
                default:
                    if (cs.LA(1) == -1) {
                        done = true;
                        break;
                    }
                    this.getInterpreter().consume(cs);
                    break;
            }
        } while (!done);
    }
}
