package io.xmake.lang.declarations.import

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Bound-name coverage for imported modules.
 *
 * These assertions keep only the bound-name behavior directly implied by
 * xmake import forms such as configured aliases, default short names, and
 * anonymous imports suppressing those default names.
 *
 * Local assignment capture and other plugin-local binding extraction behavior
 * live in [ImportBindingViewTest]. Inherited-import behavior lives in
 * [ImportInheritedExposureTest].
 */
class ImportBoundNamesTest : XMakeTestCase() {

    fun testTracksConfiguredAliasAndDefaultBoundName() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {alias = "js"})
                    import("core.base.option")
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("core.base.json", imports.resolveBoundModule("js")?.identifier)
        assertNull(imports.resolveBoundModule("json"))
        assertEquals("core.base.option", imports.resolveBoundModule("option")?.identifier)
    }

    fun testAnonymousImportDoesNotExposeDefaultName() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {anonymous = true})
                    import("core.base.option", {anonymous = true})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertNull(imports.resolveBoundModule("json"))
        assertNull(imports.resolveBoundModule("option"))
    }

    fun testDuplicateImportsPreserveAllBoundAliasesForSameModule() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    local json1 = import("core.base.json")
                    local json2 = import("core.base.json")
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("core.base.json", imports.resolveBoundModule("json1")?.identifier)
        assertEquals("core.base.json", imports.resolveBoundModule("json2")?.identifier)
        assertTrue(
            imports.importedModules
                .single { it.identifier == "core.base.json" }
                .boundNames
                .containsAll(listOf("json1", "json2", "json"))
        )
    }

    private fun configure(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }
}
