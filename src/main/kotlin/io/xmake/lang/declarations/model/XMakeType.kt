package io.xmake.lang.declarations.model

import io.xmake.lang.declarations.ApiLookupView

sealed interface XMakeType {

    enum class Primitive : XMakeType {
        STRING, NUMBER, BOOLEAN, TABLE, FUNCTION, ANY
    }

    data class Module(
        val path: String,
        val context: ApiLookupView,
        val hasUnknownMembers: Boolean = false
    ) : XMakeType {
        val topLevelModule: String get() = path.substringBefore(".")

        val isNested: Boolean get() = path.contains(".")
    }

    data class Instance(
        val typeName: String,
        val instanceName: String? = null
    ) : XMakeType

    data class Union(
        val members: List<String>
    ) : XMakeType

    data class ConfigOption(
        val optionName: String,
        val whenTrue: XMakeType? = null,
        val whenFalse: XMakeType? = null
    ) : XMakeType

    data class Function(
        val paramTypes: List<XMakeType> = emptyList(),
        val returnType: XMakeType? = null
    ) : XMakeType

    data object Unknown : XMakeType

}
