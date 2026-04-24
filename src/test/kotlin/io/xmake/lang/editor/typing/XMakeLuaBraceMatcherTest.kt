package io.xmake.lang.editor.typing

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.syntax.XMakeLuaLanguage
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeLuaBraceMatcherTest : XMakeTestCase() {

    fun testExposesLuaBracePairsAndConstructStart() {
        val matcher = XMakeLuaBraceMatcher()
        val tokenTypes = PSIElementTypeFactory.getTokenIElementTypes(XMakeLuaLanguage.INSTANCE)
        val pairs = matcher.pairs

        assertEquals(3, pairs.size)
        assertEquals(tokenTypes[LuaLexer.OP], pairs[0].leftBraceType)
        assertEquals(tokenTypes[LuaLexer.CP], pairs[0].rightBraceType)
        assertEquals(tokenTypes[LuaLexer.OB], pairs[1].leftBraceType)
        assertEquals(tokenTypes[LuaLexer.CB], pairs[1].rightBraceType)
        assertEquals(tokenTypes[LuaLexer.OCU], pairs[2].leftBraceType)
        assertEquals(tokenTypes[LuaLexer.CCU], pairs[2].rightBraceType)
        assertFalse(pairs[0].isStructural)
        assertFalse(pairs[1].isStructural)
        assertTrue(pairs[2].isStructural)

        myFixture.configureByText("xmake.lua", "target(\"demo\")")
        assertEquals(4, matcher.getCodeConstructStart(myFixture.file, 4))
        assertTrue(matcher.isPairedBracesAllowedBeforeType(pairs[0].leftBraceType, null))
    }
}
