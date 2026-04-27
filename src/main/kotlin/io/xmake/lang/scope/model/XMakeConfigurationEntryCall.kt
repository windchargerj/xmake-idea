package io.xmake.lang.scope.model

import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

internal data class XMakeConfigurationEntryCall(
    val functionName: String,
    val type: XMakeConfigurationDomainType,
    val nameArgument: NameArgument,
    val bodyShape: BodyShape
) {
    val opensConfigurationFrame: Boolean
        get() = nameArgument !is NameArgument.Invalid

    val usesRecoveryFrame: Boolean
        get() = when (nameArgument) {
            NameArgument.Missing -> true
            is NameArgument.StaticString -> nameArgument.value.isBlank()
            else -> false
        }

    val entryIssueMessage: String?
        get() = when (nameArgument) {
            NameArgument.Missing -> "$functionName() requires a name."
            is NameArgument.StaticString -> {
                if (nameArgument.value.isBlank()) "$functionName() name must not be empty." else null
            }

            NameArgument.Dynamic -> null
            NameArgument.Invalid -> "$functionName() name must be a string."
        }

    val isSelfClosingConfiguration: Boolean
        get() = bodyShape != BodyShape.PERSISTENT

    sealed interface NameArgument {
        data object Missing : NameArgument
        data class StaticString(val value: String) : NameArgument
        data object Dynamic : NameArgument
        data object Invalid : NameArgument
    }

    enum class BodyShape {
        PERSISTENT,
        TABLE,
        FUNCTION,
        DYNAMIC
    }

    companion object {
        fun from(functionName: String, functionCall: LuaFunctionCall?): XMakeConfigurationEntryCall? {
            val type = XMakeDescriptionDomainRules.configurationDomainTypeForEntry(functionName) ?: return null
            return XMakeConfigurationEntryCall(
                functionName = functionName,
                type = type,
                nameArgument = nameArgument(functionCall),
                bodyShape = bodyShape(functionCall)
            )
        }

        private fun nameArgument(functionCall: LuaFunctionCall?): NameArgument =
            when (functionCall?.argumentKind(0)) {
                LuaFunctionCall.ArgumentKind.STRING ->
                    NameArgument.StaticString(functionCall.firstStaticStringArgument.orEmpty())

                LuaFunctionCall.ArgumentKind.DYNAMIC -> NameArgument.Dynamic
                LuaFunctionCall.ArgumentKind.TABLE,
                LuaFunctionCall.ArgumentKind.FUNCTION -> NameArgument.Invalid

                null -> NameArgument.Missing
            }

        private fun bodyShape(functionCall: LuaFunctionCall?): BodyShape {
            if (functionCall == null || functionCall.arguments.size < 2) {
                return BodyShape.PERSISTENT
            }

            return when (functionCall.argumentKind(1)) {
                LuaFunctionCall.ArgumentKind.TABLE -> BodyShape.TABLE
                LuaFunctionCall.ArgumentKind.FUNCTION -> BodyShape.FUNCTION
                LuaFunctionCall.ArgumentKind.STRING,
                LuaFunctionCall.ArgumentKind.DYNAMIC,
                null -> BodyShape.DYNAMIC
            }
        }
    }
}
