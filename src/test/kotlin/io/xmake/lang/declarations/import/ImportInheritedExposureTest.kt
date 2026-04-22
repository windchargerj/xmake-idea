package io.xmake.lang.declarations.import

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Inherited import exposure coverage for the central import lookup view.
 *
 * Inherited modules stay receiver-less and only contribute merged inherited
 * APIs to the script domain.
 */
class ImportInheritedExposureTest : XMakeTestCase() {

    fun testInheritImportStaysReceiverless() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertNull(imports.resolveBoundModule("json"))
        assertNull(imports.resolveBoundModule("_super"))
        assertEquals(listOf("core.base.json"), imports.importedModules.map { it.identifier })
        assertTrue(imports.importedModules.single().boundNames.isEmpty())
        assertTrue(imports.importedModules.single().receiverNames.isEmpty())
        assertNull(imports.resolveBoundModule("_super"))
        assertNull(project.xmakeApi.imports.findReceiverModule(file, "_super"))
    }

    fun testAnonymousInheritedImportAlsoStaysReceiverless() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true, anonymous = true})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertNull(imports.resolveBoundModule("json"))
        assertNull(imports.resolveBoundModule("_super"))
        assertTrue(imports.importedModules.single().boundNames.isEmpty())
        assertTrue(imports.importedModules.single().receiverNames.isEmpty())
        assertNull(project.xmakeApi.imports.findReceiverModule(file, "_super"))
    }

    fun testXMakeApiReturnsInheritedModulesFromCentralImportLookup() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                end)
            target_end()
            """.trimIndent()
        )

        val inheritedModules = project.xmakeApi.imports.listInheritedModules(file)

        assertEquals(listOf("core.base.json"), inheritedModules.map { it.identifier })
    }

    fun testInheritedApiExposuresTrackShadowedDuplicates() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    import("core.base.json", {inherit = true})
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val exposures = requireNotNull(project.xmakeApi.imports.viewAt(file).resolveInheritedApiExposures("encode"))

        assertEquals("core.base.json.encode", exposures.primary.api.fullName)
        assertEquals(listOf("core.base.json.encode"), exposures.shadowed.map { it.api.fullName })
        assertTrue(exposures.conflicts.isEmpty())
    }

    fun testInheritedApiExposuresTrackConflictsAcrossModules() {
        myFixture.addFileToProject(
            "modules/alpha.lua",
            """
            function shared()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "modules/beta.lua",
            """
            function shared()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("alpha", {rootdir = "modules", inherit = true})
                    import("beta", {rootdir = "modules", inherit = true})
                    shared()
                end)
            target_end()
            """.trimIndent()
        )

        val exposures = requireNotNull(project.xmakeApi.imports.findInheritedApiExposures(file, "shared"))

        assertEquals("beta.shared", exposures.primary.api.fullName)
        assertTrue(exposures.shadowed.isEmpty())
        assertEquals(listOf("alpha.shared"), exposures.conflicts.map { it.api.fullName })
    }

    fun testInheritShorthandFeedsCentralInheritedModuleExposure() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    inherit("core.base.json")
                end)
            target_end()
            """.trimIndent()
        )

        val inheritedModules = project.xmakeApi.imports.listInheritedModules(file)

        assertEquals(listOf("core.base.json"), inheritedModules.map { it.identifier })
    }

    fun testInheritedImportResolvesInterfaceStyleParentFileExports() {
        myFixture.addFileToProject(
            "modules/corepack.lua",
            """
            function json.encode()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.corepack.json", {inherit = true})
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val exposures = project.xmakeApi.imports.viewAt(file).resolveInheritedApiExposures("encode")

        assertEquals("modules.corepack.json.encode", exposures?.primary?.api?.fullName)
    }

    fun testInheritShorthandResolvesInterfaceStyleParentFileExports() {
        myFixture.addFileToProject(
            "modules/corepack.lua",
            """
            function json.encode()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    inherit("modules.corepack.json")
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val exposures = project.xmakeApi.imports.viewAt(file).resolveInheritedApiExposures("encode")

        assertEquals("modules.corepack.json.encode", exposures?.primary?.api?.fullName)
    }

    private fun configure(code: String): XMakeLuaFile {
        return configureXMakeLua(code)
    }
}
