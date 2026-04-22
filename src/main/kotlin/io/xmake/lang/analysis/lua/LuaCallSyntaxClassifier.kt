package io.xmake.lang.analysis.lua

import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

internal sealed class LuaCallSyntaxKind {
    data object MemberCall : LuaCallSyntaxKind()
    data object PlainCall : LuaCallSyntaxKind()
    data object None : LuaCallSyntaxKind()
}

internal object LuaCallSyntaxClassifier {

    fun classify(element: XMakeLuaIdentifier): LuaCallSyntaxKind {
        if (!PsiPredicates.isFunctionCall(element)) {
            return LuaCallSyntaxKind.None
        }
        if (LuaCallChainResolver.resolve(element) != null) {
            return LuaCallSyntaxKind.MemberCall
        }
        return LuaCallSyntaxKind.PlainCall
    }
}
