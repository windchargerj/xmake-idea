package io.xmake.lang.editor.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object XMakeLuaTextAttribute {
    val IDENTIFIER: TextAttributesKey = DefaultLanguageHighlighterColors.IDENTIFIER
    val KEYWORD: TextAttributesKey = DefaultLanguageHighlighterColors.KEYWORD
    val STRING: TextAttributesKey = DefaultLanguageHighlighterColors.STRING
    val NUMBER: TextAttributesKey = DefaultLanguageHighlighterColors.NUMBER
    val COMMENT: TextAttributesKey = DefaultLanguageHighlighterColors.LINE_COMMENT
    val FUNCTION_CALL: TextAttributesKey = DefaultLanguageHighlighterColors.FUNCTION_CALL

    val OPERATION_SIGN: TextAttributesKey = DefaultLanguageHighlighterColors.OPERATION_SIGN
    val BRACKETS: TextAttributesKey = DefaultLanguageHighlighterColors.BRACKETS
    val PARENTHESES: TextAttributesKey = DefaultLanguageHighlighterColors.PARENTHESES
    val COMMA: TextAttributesKey = DefaultLanguageHighlighterColors.COMMA
    val DOT: TextAttributesKey = DefaultLanguageHighlighterColors.DOT

    val BAD_CHARACTER: TextAttributesKey = HighlighterColors.BAD_CHARACTER

    val XMAKE_LUA_FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_FUNCTION_CALL", FUNCTION_CALL
    )
    val XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL", FUNCTION_CALL
    )
    val XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL", FUNCTION_CALL
    )
    val XMAKE_LUA_DESCRIPTION_API_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_DESCRIPTION_API_CALL", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )
    val XMAKE_LUA_SCRIPT_API_CALL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_SCRIPT_API_CALL", FUNCTION_CALL
    )
    val XMAKE_LUA_CONFIGURATION_DOMAIN_API = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_CONFIGURATION_DOMAIN_API", DefaultLanguageHighlighterColors.CLASS_NAME
    )
    val XMAKE_LUA_INSTANCE_METHOD = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_INSTANCE_METHOD", FUNCTION_CALL
    )
    val XMAKE_LUA_FUNCTION_DECLARATION = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_FUNCTION_DECLARATION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    )
    val XMAKE_LUA_TABLE_KEY = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_TABLE_KEY", DefaultLanguageHighlighterColors.METADATA
    )
    val XMAKE_LUA_TABLE_FIELD = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_TABLE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )

    // Lua: local x = 1
    val XMAKE_LUA_LOCAL_VARIABLE = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    )

    // Lua: function foo(a, b, c)
    val XMAKE_LUA_PARAMETER = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER
    )

    // Lua: ::label::
    val XMAKE_LUA_LABEL = TextAttributesKey.createTextAttributesKey(
        "XMAKE_LUA_LABEL", DefaultLanguageHighlighterColors.LABEL
    )
}
