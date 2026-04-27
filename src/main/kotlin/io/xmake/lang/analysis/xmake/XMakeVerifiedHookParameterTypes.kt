package io.xmake.lang.analysis.xmake

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaFunctionBody
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaParameterList

object XMakeVerifiedHookParameterTypes {

    fun resolveVerifiedParameterType(identifier: XMakeLuaIdentifier): XMakeType? {
        return resolveVerifiedParameterType(identifier, api = null)
    }

    fun resolveVerifiedParameterType(identifier: XMakeLuaIdentifier, api: XMakeApi?): XMakeType? {
        api ?: return null
        val functionBody = PsiTreeUtil.getParentOfType(identifier, LuaFunctionBody::class.java) ?: return null
        val parameterIndex = parameterIndex(functionBody, identifier) ?: return null
        if (parameterIndex != 0) {
            return null
        }

        val hookCall = PsiTreeUtil.getParentOfType(functionBody, LuaFunctionCall::class.java) ?: return null
        val hookName = hookCall.calleeName ?: return null
        if (hookCall.arguments.none { argument ->
                PsiTreeUtil.findChildOfType(argument, LuaFunctionBody::class.java) == functionBody
            }
        ) {
            return null
        }

        val domainType = (XMakeScopeQuery.stateAt(hookCall).domain as? XMakeDomain.Configuration)?.type
            ?: return null
        val receiverType = verifiedPrimaryReceiverType(domainType, hookName, api) ?: return null
        return XMakeType.Instance(receiverType)
    }

    private fun parameterIndex(functionBody: LuaFunctionBody, identifier: XMakeLuaIdentifier): Int? {
        val parameterList = PsiTreeUtil.findChildOfType(functionBody, LuaParameterList::class.java) ?: return null
        val parameters = PsiTreeUtil.findChildrenOfType(parameterList, XMakeLuaIdentifier::class.java)
            .sortedBy { it.textOffset }
        return parameters.indexOfFirst { it == identifier }.takeIf { it >= 0 }
    }

    private fun verifiedPrimaryReceiverType(
        domainType: XMakeConfigurationDomainType,
        hookName: String,
        api: XMakeApi
    ): String? {
        val receiverType = when (domainType) {
            XMakeConfigurationDomainType.TARGET -> "target"
            XMakeConfigurationDomainType.OPTION -> "option"
            XMakeConfigurationDomainType.PACKAGE -> "package"
            XMakeConfigurationDomainType.TOOLCHAIN -> "toolchain"
            XMakeConfigurationDomainType.RULE -> return null
            XMakeConfigurationDomainType.TASK -> return null
        }
        if (receiverType !in api.instanceTypes()) {
            return null
        }
        val apiFullName = "${domainType.toKeyword()}.$hookName"
        if (api.findApiByFullName(apiFullName) == null) {
            return null
        }
        return receiverType.takeIf { hookName.isVerifiedHookName }
    }

    private val String.isVerifiedHookName: Boolean
        get() = startsWith("on_") || startsWith("before_") || startsWith("after_")
            || this == "on_load" || this == "on_check"
}
