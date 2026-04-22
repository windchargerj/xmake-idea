package io.xmake.lang.editor.settings.codeStyle

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.*
import io.xmake.lang.syntax.XMakeLuaLanguage

class XMakeCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions
    ) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.KEEP_INDENTS_ON_EMPTY_LINES = false
        indentOptions.USE_TAB_CHARACTER = false

        commonSettings.SPACE_AROUND_ASSIGNMENT_OPERATORS = true
        commonSettings.SPACE_AROUND_LOGICAL_OPERATORS = true
        commonSettings.SPACE_AROUND_EQUALITY_OPERATORS = true
        commonSettings.SPACE_AROUND_RELATIONAL_OPERATORS = true
        commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS = true
        commonSettings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = true
        commonSettings.SPACE_AROUND_BITWISE_OPERATORS = true
        commonSettings.SPACE_AFTER_COMMA = true
        commonSettings.SPACE_BEFORE_COMMA = false
        commonSettings.SPACE_BEFORE_METHOD_CALL_PARENTHESES = false
        commonSettings.SPACE_BEFORE_METHOD_PARENTHESES = false
        commonSettings.SPACE_WITHIN_PARENTHESES = false
        commonSettings.SPACE_WITHIN_BRACKETS = false
        commonSettings.SPACE_WITHIN_BRACES = false

        commonSettings.KEEP_BLANK_LINES_IN_CODE = 1
        commonSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 1
        commonSettings.KEEP_BLANK_LINES_BEFORE_RBRACE = 0
        commonSettings.KEEP_LINE_BREAKS = true
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        when (settingsType) {
            SettingsType.SPACING_SETTINGS -> {
                consumer.showStandardOptions(
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_ASSIGNMENT_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_LOGICAL_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_EQUALITY_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_RELATIONAL_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_ADDITIVE_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_MULTIPLICATIVE_OPERATORS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AROUND_BITWISE_OPERATORS.name,
                )

                consumer.showStandardOptions(
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_AFTER_COMMA.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_BEFORE_COMMA.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_BEFORE_METHOD_CALL_PARENTHESES.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_BEFORE_METHOD_PARENTHESES.name,
                )

                consumer.showStandardOptions(
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_WITHIN_PARENTHESES.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_WITHIN_BRACKETS.name,
                    CodeStyleSettingsCustomizable.SpacingOption.SPACE_WITHIN_BRACES.name,
                )
            }

            SettingsType.BLANK_LINES_SETTINGS -> {
                consumer.showStandardOptions(
                    CodeStyleSettingsCustomizable.BlankLinesOption.KEEP_BLANK_LINES_IN_CODE.name,
                    CodeStyleSettingsCustomizable.BlankLinesOption.KEEP_BLANK_LINES_IN_DECLARATIONS.name,
                    CodeStyleSettingsCustomizable.BlankLinesOption.KEEP_BLANK_LINES_BEFORE_RBRACE.name,
                )
            }

            else -> {}
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor {
        return SmartIndentOptionsEditor()
    }

    override fun getLanguage(): Language {
        return XMakeLuaLanguage.INSTANCE
    }

    override fun createConfigurable(
        baseSettings: CodeStyleSettings,
        modelSettings: CodeStyleSettings
    ): CodeStyleConfigurable {
        return XMakeCodeStyleConfigurable(baseSettings, modelSettings)
    }

    override fun getCodeSample(settingsType: SettingsType): String {
        return when (settingsType) {
            SettingsType.INDENT_SETTINGS -> INDENT_SAMPLE
            SettingsType.SPACING_SETTINGS -> SPACING_SAMPLE
            SettingsType.BLANK_LINES_SETTINGS -> BLANK_LINES_SAMPLE
            else -> DEFAULT_SAMPLE
        }
    }

    companion object {
        private val DEFAULT_SAMPLE = """
target("test")
    set_kind("binary")
    add_files("src/*.c")
    add_includedirs("include")

    if is_mode("debug") then
        add_defines("DEBUG")
    end
target_end()

local sources = {"a.c", "b.c", "c.c"}
for _, source in ipairs(sources) do
    print("Compiling: " .. source)
end
""".trimIndent()

        private val INDENT_SAMPLE = """
target("example")
    set_kind("binary")
    add_files("src/*.c")

    on_load(function(target)
        if is_mode("debug") then
            target:add("defines", "DEBUG")
        end
    end)

    after_build(function(target)
        print("Build completed!")
    end)
target_end()
""".trimIndent()

        private val SPACING_SAMPLE = """
-- Assignment and arithmetic operators
local x = 1 + 2
local y = x * 3 - 1
local z = y / 2 % 3
local pow = x ^ 2
local neg = -x
local mask = ~bits

-- Relational and equality operators
if x > 0 and y <= 10 then
    print(x == y)
end

-- Bitwise operators
local bits = x & 0xFF | y << 2

-- Tables and function calls
local t = {a = 1, b = 2, c = 3}
local arr = {1, 2, 3, 4, 5}
print(t[1], arr[2])

-- String concatenation
local str = "Hello" .. " " .. "World"

-- Function definition
function add(a, b)
    return a + b
end
""".trimIndent()

        private val BLANK_LINES_SAMPLE = """
-- Module definition
local module = {}


function module.init()
    print("Initializing...")
end


function module.run()
    print("Running...")
end


function module.cleanup()
    print("Cleaning up...")
end


return module
""".trimIndent()
    }
}

