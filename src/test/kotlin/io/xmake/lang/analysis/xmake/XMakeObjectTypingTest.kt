package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.declarations.model.XMakeType

/**
 * Validates the conservative object typing boundary.
 *
 * `xmake show -l apis` proves instance method names. It does not expose return-type
 * metadata, so member calls must not synthesize return types locally.
 */
class XMakeObjectTypingTest : XMakeTestCase() {

    fun testShowApiSurfaceContainsInstanceMethodsWithoutReturnTypes() {
        val targetMethods = project.xmakeApi.instanceApis("target", ApiLookupView.SCRIPT_GLOBAL_ROOT)

        assertTrue(targetMethods.any { it.fullName == "target:dep" })
        assertTrue(targetMethods.any { it.fullName == "target:name" })
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("target"), "dep"))
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("target"), "name"))
    }

    fun testShowApiSurfaceContainsOtherInstanceMethodsWithoutReturnTypes() {
        assertTrue(project.xmakeApi.instanceApis("package", ApiLookupView.SCRIPT_GLOBAL_ROOT)
            .any { it.fullName == "package:dep" })
        assertTrue(project.xmakeApi.instanceApis("option", ApiLookupView.SCRIPT_GLOBAL_ROOT)
            .any { it.fullName == "option:dep" })
        assertTrue(project.xmakeApi.instanceApis("rule", ApiLookupView.SCRIPT_GLOBAL_ROOT)
            .any { it.fullName == "rule:clone" })

        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("package"), "dep"))
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("option"), "dep"))
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("rule"), "clone"))
    }

    fun testUnknownMembersReturnNull() {
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Instance("target"), "enabled"))
        assertNull(project.xmakeApi.typeResolver.resolveMemberReturnType(XMakeType.Module("path", ApiLookupView.SCRIPT_GLOBAL_ROOT), "join", "."))
    }
}
