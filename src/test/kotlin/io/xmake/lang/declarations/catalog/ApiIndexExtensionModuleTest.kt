package io.xmake.lang.declarations.catalog

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.XMakeApi

class ApiIndexExtensionModuleTest : XMakeTestCase() {

    fun testEnumeratesExtensionChildModules() {
        val lookup = XMakeApi.getInstance(project).lookup

        assertTrue(lookup.extensionChildModules("core").contains("base"))
        assertTrue(lookup.extensionChildModules("core.base").contains("json"))
        assertFalse(lookup.extensionChildModules("core.base.json").contains("json"))
        assertTrue(
            lookup.importableModuleApis("core.base.json")
                .map { it.name }
                .toSet()
                .containsAll(setOf("decode", "encode", "loadfile", "savefile", "mark_as_array", "is_marked_as_array"))
        )
    }

    fun testDetectsExtensionModuleOrChildren() {
        val lookup = XMakeApi.getInstance(project).lookup

        assertTrue(lookup.hasExtensionModuleOrChildren("core"))
        assertTrue(lookup.hasExtensionModuleOrChildren("core.base"))
        assertTrue(lookup.hasExtensionModuleOrChildren("core.base.json"))
        assertFalse(lookup.hasExtensionModuleOrChildren("core.base.missing"))
    }

    fun testSplitsModulePathViewsBySemanticBucket() {
        val lookup = XMakeApi.getInstance(project).lookup

        assertTrue("core.base.json" in lookup.extensionModulePaths())
        assertTrue(lookup.descriptionBuiltinModulePaths().isNotEmpty())
        assertTrue(lookup.scriptBuiltinModulePaths().isNotEmpty())
        assertEquals(lookup.descriptionBuiltinModulePaths(), lookup.visibleBuiltinModulePaths(io.xmake.lang.declarations.ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
        assertEquals(lookup.scriptBuiltinModulePaths(), lookup.visibleBuiltinModulePaths(io.xmake.lang.declarations.ApiLookupView.SCRIPT_GLOBAL_ROOT))
        assertTrue(lookup.indexedModulePaths().containsAll(lookup.descriptionBuiltinModulePaths()))
        assertTrue(lookup.indexedModulePaths().containsAll(lookup.scriptBuiltinModulePaths()))
        assertTrue(lookup.indexedModulePaths().containsAll(lookup.extensionModulePaths()))
    }
}
