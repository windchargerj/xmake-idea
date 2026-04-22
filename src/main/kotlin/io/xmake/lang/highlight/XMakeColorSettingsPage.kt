package io.xmake.lang.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.xmake.icons.XMakeIcons
import io.xmake.lang.XMakeLuaLanguage
import javax.swing.Icon

class XMakeColorSettingsPage : ColorSettingsPage {
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? {
        return null
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
target("hello")

    -- set kind
    set_kind("binary")

    -- add files
    add_files("src/*.c")

    -- add definitions
    add_defines("NDEBUG")

    -- add includedirs
    add_includedirs("##(includedir)")
    add_includedirs("$(env BOOST_INCLUDE_DIR)", "$(env BOOST_HOME)/include")

-- a another target
target("another")

    -- set kind
    set_kind("static")

    -- add files
    add_files("src/another.c")

-- the options
option("test")

    -- set show menu
    set_showmenu(true)

    -- set default
    set_default(false)

    -- set description
    set_description("Enable Test")
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
            AttributesDescriptor("Identifier", XMakeLuaTextAttribute.IDENTIFIER),
            AttributesDescriptor("Keyword", XMakeLuaTextAttribute.KEYWORD),
            AttributesDescriptor("String", XMakeLuaTextAttribute.STRING),
            AttributesDescriptor("Comment", XMakeLuaTextAttribute.COMMENT),
        )
    }
}