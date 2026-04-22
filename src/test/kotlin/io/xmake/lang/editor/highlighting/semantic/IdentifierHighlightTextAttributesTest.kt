package io.xmake.lang.editor.highlighting.semantic

import io.xmake.lang.analysis.model.IdentifierHighlightKind
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.IdentifierStructuralKind
import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute
import junit.framework.TestCase

class IdentifierHighlightTextAttributesTest : TestCase() {

    fun testAllHighlightKindsHaveUiTextAttributes() {
        IdentifierHighlightKind.entries.forEach { type ->
            assertNotNull(
                "Missing UI text attributes mapping for $type",
                IdentifierHighlightTextAttributes.get(type)
            )
        }
    }

    fun testRepresentativeIdentifierKindsMapToExpectedKeys() {
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION,
            IdentifierHighlightTextAttributes.get(IdentifierSemanticKind.FUNCTION_DECLARATION)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL,
            IdentifierHighlightTextAttributes.get(IdentifierSemanticKind.SCRIPT_API_CALL)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
            IdentifierHighlightTextAttributes.get(IdentifierStructuralKind.CONFIGURATION_DOMAIN_ENTRY)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
            IdentifierHighlightTextAttributes.get(IdentifierStructuralKind.NAMESPACE_END)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL,
            IdentifierHighlightTextAttributes.get(IdentifierSemanticKind.MODULE_CALL)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD,
            IdentifierHighlightTextAttributes.get(IdentifierSemanticKind.TABLE_FIELD)
        )
        assertEquals(
            XMakeLuaTextAttribute.XMAKE_LUA_LABEL,
            IdentifierHighlightTextAttributes.get(IdentifierSemanticKind.LABEL)
        )
    }
}
