package io.xmake.lang.editor.highlighting.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import io.xmake.lang.analysis.model.IdentifierHighlightKind
import io.xmake.lang.analysis.model.IdentifierSemanticKind
import io.xmake.lang.analysis.model.IdentifierStructuralKind
import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute

internal object IdentifierHighlightTextAttributes {

    private val mapping: Map<IdentifierHighlightKind, TextAttributesKey> = mapOf(
        IdentifierSemanticKind.FUNCTION_DECLARATION to XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION,
        IdentifierSemanticKind.FUNCTION_CALL to XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL,
        IdentifierSemanticKind.DESCRIPTION_BUILTIN_FUNCTION_CALL to XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL,
        IdentifierSemanticKind.SCRIPT_BUILTIN_FUNCTION_CALL to XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL,
        IdentifierSemanticKind.DESCRIPTION_API_CALL to XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
        IdentifierSemanticKind.SCRIPT_API_CALL to XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL,
        IdentifierSemanticKind.MODULE_CALL to XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL,
        IdentifierSemanticKind.INSTANCE_METHOD to XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD,
        IdentifierStructuralKind.CONFIGURATION_DOMAIN_ENTRY to XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
        IdentifierStructuralKind.CONFIGURATION_DOMAIN_END to XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
        IdentifierStructuralKind.NAMESPACE_ENTRY to XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
        IdentifierStructuralKind.NAMESPACE_END to XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API,
        IdentifierSemanticKind.TABLE_KEY to XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY,
        IdentifierSemanticKind.TABLE_FIELD to XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD,
        IdentifierSemanticKind.LOCAL_VARIABLE to XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE,
        IdentifierSemanticKind.PARAMETER to XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER,
        IdentifierSemanticKind.LABEL to XMakeLuaTextAttribute.XMAKE_LUA_LABEL,
    )

    init {
        check(mapping.keys == IdentifierHighlightKind.entries) {
            val missing = IdentifierHighlightKind.entries.filterNot(mapping::containsKey)
            "Missing semantic highlighting mapping for $missing"
        }
    }

    fun get(type: IdentifierHighlightKind): TextAttributesKey = mapping.getValue(type)
}
