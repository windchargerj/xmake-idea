package io.xmake.lang.analysis.xmake

import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

object XMakeVerifiedHookParameterTypes {

    fun resolveVerifiedParameterType(identifier: XMakeLuaIdentifier): XMakeType? {
        return resolveVerifiedParameterType(identifier, api = null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveVerifiedParameterType(identifier: XMakeLuaIdentifier, api: XMakeApi?): XMakeType? {
        return null
    }
}
