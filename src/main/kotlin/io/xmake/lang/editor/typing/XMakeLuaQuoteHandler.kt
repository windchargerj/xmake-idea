package io.xmake.lang.editor.typing

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.syntax.XMakeLuaLanguage
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeLuaQuoteHandler : SimpleTokenSetQuoteHandler(
    PSIElementTypeFactory.createTokenSet(
        XMakeLuaLanguage.INSTANCE,
        LuaLexer.NORMALSTRING,
        LuaLexer.CHARSTRING
    )
)
