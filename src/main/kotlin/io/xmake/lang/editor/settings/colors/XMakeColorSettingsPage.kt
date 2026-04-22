package io.xmake.lang.editor.settings.colors

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.xmake.icons.XMakeIcons
import io.xmake.lang.editor.highlighting.XMakeLuaSyntaxHighlighter
import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute
import io.xmake.lang.syntax.XMakeLuaLanguage
import javax.swing.Icon

class XMakeColorSettingsPage : ColorSettingsPage {
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? {
        return mapOf(
            "luaFunction" to XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL,
            "descriptionApi" to XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
            "scriptApi" to XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL,
        "configurationDomain" to XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
            "instanceMethod" to XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD,
            "functionDecl" to XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION,
            "tableKey" to XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY,
            "tableField" to XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD,
            "localVar" to XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE,
            "parameter" to XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER,
            "label" to XMakeLuaTextAttribute.XMAKE_LUA_LABEL,
        )
    }

    override fun getIcon(): Icon {
        return XMakeIcons.XMAKE
    }

    override fun getHighlighter(): SyntaxHighlighter {
        return XMakeLuaSyntaxHighlighter()
    }

    override fun getDemoText(): String {
        return """
-- add target
            <configurationDomain>target</configurationDomain>("hello")

    -- set kind
    <descriptionApi>set_kind</descriptionApi>("binary")

    -- add files
    <descriptionApi>add_files</descriptionApi>("src/*.c")

    -- hooks use script APIs
    <descriptionApi>after_build</descriptionApi>(function (<parameter>target</parameter>)
        local <localVar>j</localVar> = <scriptApi>import</scriptApi>("core.base.json", {<tableKey>alias</tableKey> = "j"})
        local <localVar>payload</localVar> = {<tableKey>name</tableKey> = <parameter>target</parameter>:<instanceMethod>name</instanceMethod>()}
        local <localVar>home</localVar> = os.<luaFunction>getenv</luaFunction>("HOME")
        <localVar>j</localVar>.<luaFunction>encode</luaFunction>(<localVar>payload</localVar>)
        <localVar>payload</localVar>.<tableField>name</tableField> = "hello"
        goto <label>done</label>
        ::<label>done</label>::
    end)

local function <functionDecl>helper</functionDecl>(<parameter>message</parameter>)
    return <parameter>message</parameter>
end

<luaFunction>helper</luaFunction>("ok")
"""
    }

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> {
        return DESCRIPTORS
    }

    override fun getColorDescriptors(): Array<ColorDescriptor> {
        return ColorDescriptor.EMPTY_ARRAY
    }

    override fun getDisplayName(): String {
        return XMakeLuaLanguage.displayName
    }

    companion object {
        private val DESCRIPTORS = arrayOf<AttributesDescriptor>(
            AttributesDescriptor("Keyword", XMakeLuaTextAttribute.KEYWORD),
            AttributesDescriptor("String", XMakeLuaTextAttribute.STRING),
            AttributesDescriptor("Number", XMakeLuaTextAttribute.NUMBER),
            AttributesDescriptor("Comment", XMakeLuaTextAttribute.COMMENT),
            AttributesDescriptor("Identifier", XMakeLuaTextAttribute.IDENTIFIER),
            AttributesDescriptor("Operation sign", XMakeLuaTextAttribute.OPERATION_SIGN),
            AttributesDescriptor("Brackets", XMakeLuaTextAttribute.BRACKETS),
            AttributesDescriptor("Parentheses", XMakeLuaTextAttribute.PARENTHESES),
            AttributesDescriptor("Comma", XMakeLuaTextAttribute.COMMA),
            AttributesDescriptor("Dot", XMakeLuaTextAttribute.DOT),
            AttributesDescriptor("Bad character", XMakeLuaTextAttribute.BAD_CHARACTER),
            AttributesDescriptor("Lua function call", XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL),
            AttributesDescriptor("Description domain API call", XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL),
            AttributesDescriptor("Script domain API call", XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL),
            AttributesDescriptor("Function declaration", XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION),
        AttributesDescriptor("Configuration domain API", XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API),
            AttributesDescriptor("Instance method call", XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD),
            AttributesDescriptor("Table key", XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY),
            AttributesDescriptor("Table field", XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD),
            AttributesDescriptor("Local variable", XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE),
            AttributesDescriptor("Parameter", XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER),
            AttributesDescriptor("Label", XMakeLuaTextAttribute.XMAKE_LUA_LABEL),
        )
    }
}
