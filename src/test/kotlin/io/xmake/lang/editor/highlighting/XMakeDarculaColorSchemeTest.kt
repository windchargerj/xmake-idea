package io.xmake.lang.editor.highlighting

import junit.framework.TestCase
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Theme contract coverage for the bundled xmake color scheme.
 *
 * These tests protect editor presentation choices and stale key cleanup rather
 * than xmake language behavior.
 */
class XMakeDarculaColorSchemeTest : TestCase() {

    fun testDarculaSchemeCoversSemanticHighlightKeys() {
        val options = loadSchemeOptions()
        val expectedKeys = setOf(
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER.externalName,
            XMakeLuaTextAttribute.XMAKE_LUA_LABEL.externalName,
        )

        val configuredKeys = options.keys
        assertTrue(
            "Missing Darcula text attributes: ${(expectedKeys - configuredKeys).sorted()}",
            configuredKeys.containsAll(expectedKeys)
        )

        val unexpectedKeys = configuredKeys
            .filter { it.startsWith("XMAKE_LUA_") }
            .toSet() - expectedKeys
        assertTrue("Unexpected stale Darcula text attributes: ${unexpectedKeys.sorted()}", unexpectedKeys.isEmpty())
    }

    fun testDarculaSchemeConfiguresSemanticPaletteViaForegroundOrBaseAttributes() {
        val options = loadSchemeOptions()
        val semanticKeys = listOf(
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
            XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD,
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION,
            XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY,
            XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD,
            XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE,
            XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER,
            XMakeLuaTextAttribute.XMAKE_LUA_LABEL,
        )

        semanticKeys.forEach { key ->
            val option = options.getValue(key.externalName)
            assertTrue(
                "${key.externalName} should configure a foreground or inherit base attributes",
                option.foreground != null || option.baseAttributes != null
            )
        }
    }

    fun testDarculaSchemeKeepsConfigurationDomainStyleEmphasis() {
        val options = loadSchemeOptions()
        val domain = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API.externalName)

        assertNotNull("Configuration domain API should keep a custom foreground", domain.foreground)
        assertEquals("Configuration domain API should be bold italic", "3", domain.fontType)
    }

    fun testDarculaSchemeSeparatesDescriptionAndScriptDomainApisWithoutCustomizingFunctionCalls() {
        val options = loadSchemeOptions()

        val plainFunction = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL.externalName)
        val descriptionBuiltin = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL.externalName)
        val scriptBuiltin = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL.externalName)
        val descriptionApi = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL.externalName)
        val scriptApi = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL.externalName)

        assertNull("Plain Lua function call should stay theme-aligned", plainFunction.foreground)
        assertNotNull("Description builtin function call should use a dedicated foreground", descriptionBuiltin.foreground)
        assertNull("Description builtin function call should not be italic", descriptionBuiltin.fontType)
        assertNotNull("Script builtin function call should use a dedicated foreground", scriptBuiltin.foreground)
        assertNull("Script builtin function call should not be italic", scriptBuiltin.fontType)
        assertNotNull("Description domain API call should use a dedicated DSL foreground", descriptionApi.foreground)
        assertEquals("Description domain API call should be italic", "2", descriptionApi.fontType)
        assertFalse(
            "Description builtin function call should differ from the description API color",
            descriptionApi.foreground == descriptionBuiltin.foreground
        )
        assertFalse(
            "Script API call should differ from the description API color",
            descriptionApi.foreground == scriptApi.foreground
        )
        assertNull("Script API call should not be italic", scriptApi.fontType)
    }

    fun testDarculaSchemeKeepsInstanceMethodThemeAligned() {
        val options = loadSchemeOptions()
        val instanceMethod = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD.externalName)

        assertEquals(
            "Instance method should inherit regular function-call styling",
            "FUNCTION_CALL",
            instanceMethod.baseAttributes
        )
        assertNull("Instance method should not override the theme foreground", instanceMethod.foreground)
        assertNull("Instance method should not force italics or bold", instanceMethod.fontType)
    }

    fun testDarculaSchemeSeparatesTableKeyFromDslCalls() {
        val options = loadSchemeOptions()
        val tableKey = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY.externalName)
        val descriptionApi = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL.externalName)

        assertNotNull("Table key should keep a dedicated foreground", tableKey.foreground)
        assertFalse(
            "Table key should not reuse the description-domain API color",
            descriptionApi.foreground == tableKey.foreground
        )
    }

    fun testDarculaSchemeConfiguresTableKeyWithoutKotlinBaseAttributes() {
        val options = loadSchemeOptions()

        val tableKey = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY.externalName)

        assertNull("Table key highlight should not inherit Kotlin attributes", tableKey.baseAttributes)
        assertNotNull("Table key highlight should configure a direct foreground", tableKey.foreground)
    }

    fun testDarculaSchemeWiresTableFieldToBaseAttributes() {
        val options = loadSchemeOptions()

        val tableField = options.getValue(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD.externalName)

        assertNotNull("Table field highlight should inherit a base attribute", tableField.baseAttributes)
    }

    private fun loadSchemeOptions(): Map<String, SchemeOption> {
        val stream = javaClass.classLoader.getResourceAsStream("colorSchemes/xmake-lua.xml")
            ?: throw AssertionError("Unable to load Darcula color scheme resource")
        return stream.use(::parseSchemeOptions)
    }

    private fun parseSchemeOptions(stream: InputStream): Map<String, SchemeOption> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(stream)
        val nodes = document.getElementsByTagName("option")
        val result = linkedMapOf<String, SchemeOption>()

        for (index in 0 until nodes.length) {
            val option = nodes.item(index) as? Element ?: continue
            val name = option.getAttribute("name")
            if (!name.startsWith("XMAKE_LUA_")) {
                continue
            }
            val baseAttributes = option.getAttribute("baseAttributes").takeIf { it.isNotBlank() }
            val foreground = findOptionValue(option, "FOREGROUND")
            val fontType = findOptionValue(option, "FONT_TYPE")
            result[name] = SchemeOption(baseAttributes, foreground, fontType)
        }

        return result
    }

    private fun findOptionValue(option: Element, name: String): String? {
        val nodes = option.getElementsByTagName("option")
        for (index in 0 until nodes.length) {
            val child = nodes.item(index) as? Element ?: continue
            if (child.getAttribute("name") == name) {
                return child.getAttribute("value")
            }
        }
        return null
    }

    private data class SchemeOption(
        val baseAttributes: String?,
        val foreground: String?,
        val fontType: String?,
    )
}
