package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Inherited imports merge APIs into the script domain without exposing a synthetic
 * module receiver.
 */
class XMakeModuleInheritedImportLookupTest : XMakeTestCase() {

    fun testInheritedImportDoesNotExposeModuleReceiverBindings() {
        val api = XMakeApi.getInstance(project)
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(api.resolveVisibleModulePath("_super", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModule("_super", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertNull(api.resolveVisibleModulePath("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModule("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testInheritedImportDoesNotEnumerateModuleFunctionsThroughReceiverBindings() {
        val api = XMakeApi.getInstance(project)
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        assertTrue(api.visibleModuleFunctions("_super", ApiLookupView.SCRIPT_GLOBAL_ROOT, file).isEmpty())
        assertTrue(api.visibleModuleFunctions("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file).isEmpty())
    }

    fun testInheritShorthandDoesNotExposeModuleReceiverBindings() {
        val api = XMakeApi.getInstance(project)
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    inherit("core.base.json")
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(api.resolveVisibleModulePath("_super", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModule("_super", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertNull(api.resolveVisibleModulePath("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModule("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    private fun configure(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }
}
