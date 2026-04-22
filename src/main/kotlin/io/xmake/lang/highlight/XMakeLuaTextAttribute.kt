package io.xmake.lang.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object XMakeLuaTextAttribute {
    val IDENTIFIER: TextAttributesKey = DefaultLanguageHighlighterColors.IDENTIFIER

    val KEYWORD: TextAttributesKey = DefaultLanguageHighlighterColors.KEYWORD

    val STRING: TextAttributesKey = DefaultLanguageHighlighterColors.STRING

    val NUMBER: TextAttributesKey = DefaultLanguageHighlighterColors.NUMBER

    val COMMENT: TextAttributesKey = DefaultLanguageHighlighterColors.LINE_COMMENT

    val FUNCTION_CALL: TextAttributesKey = DefaultLanguageHighlighterColors.FUNCTION_CALL

    val XMAKE_LUA_FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_FUNCTION_CALL", FUNCTION_CALL
    )
    val XMAKE_LUA_DOMAIN_SCOPE = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_DOMAIN_SCOPE", DefaultLanguageHighlighterColors.CLASS_NAME
    )
    val XMAKE_LUA_INSTANCE = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_INSTANCE", DefaultLanguageHighlighterColors.IDENTIFIER
    )
    val XMAKE_LUA_INSTANCE_METHOD = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD
    )
}