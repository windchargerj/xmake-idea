package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Doc-backed module lookup coverage for xmake imports.
 *
 * This suite keeps only import forms and resolvable module paths that are directly
 * described in xmake documentation, such as direct imports, aliases, and root
 * module imports like `import("core")`.
 *
 */
class XMakeModuleLookupTest : XMakeTestCase() {

    fun testDoesNotTreatUnimportedExtensionModuleAsVisible() {
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    json.encode({})
                end)
            target_end()
        """.trimIndent())

        assertFalse(api.isVisibleModule("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModulePath("core.base", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testTreatsImportedExtensionModuleShortNameAsVisible() {
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.encode({})
                end)
            target_end()
        """.trimIndent())

        assertEquals("core.base.json", api.resolveVisibleModulePath("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertTrue(api.isVisibleModule("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testTreatsImportedAliasAsVisible() {
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("core.base.json", {alias = "j"})
                    j.encode({})
                end)
            target_end()
        """.trimIndent())

        assertEquals("core.base.json", api.resolveVisibleModulePath("j", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertTrue(api.isVisibleModule("j", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testDoesNotTreatInheritedImportShortNameAsVisible() {
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    json.encode({})
                end)
            target_end()
        """.trimIndent())

        assertNull(api.resolveVisibleModulePath("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertFalse(api.isVisibleModule("json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testTreatsImportedRootModuleSubpathAsVisible() {
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("core")
                    core.base.json.encode({})
                end)
            target_end()
        """.trimIndent())

        assertEquals("core.base.json", api.resolveVisibleModulePath("core.base.json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertTrue(api.isVisibleModulePath("core.base", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    fun testTreatsImportedLocalModuleAsVisibleAndUsesLocalFunctions() {
        myFixture.addFileToProject(
            "modules/hello1.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("modules.hello1")
                    hello1.greet()
                end)
            target_end()
        """.trimIndent())

        assertEquals("modules.hello1", api.resolveVisibleModulePath("hello1", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertTrue(api.isVisibleModule("hello1", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertEquals(listOf("greet"), api.visibleModuleFunctions("hello1", ApiLookupView.SCRIPT_GLOBAL_ROOT, file).map { it.name })
    }

    fun testTreatsRootDirImportedLocalModuleAsVisible() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val api = XMakeApi.getInstance(project)
        val file = configure("""
            target("demo")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hello3.greet()
                end)
            target_end()
        """.trimIndent())

        assertEquals("hello3", api.resolveVisibleModulePath("hello3", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
        assertTrue(api.isVisibleModule("hello3", ApiLookupView.SCRIPT_GLOBAL_ROOT, file))
    }

    private fun configure(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }
}
