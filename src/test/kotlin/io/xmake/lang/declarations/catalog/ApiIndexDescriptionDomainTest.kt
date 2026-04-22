package io.xmake.lang.declarations.catalog

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot

class ApiIndexDescriptionDomainTest : XMakeTestCase() {

    fun testDeduplicatesDescriptionConfigurationItemsFromCatalog() {
        val lookup = project.xmakeApi.lookup

        assertEquals(1, lookup.configurationItems(XMakeConfigurationDomainType.OPTION).count { it.name == "add_includedirs" })
        assertEquals(1, lookup.configurationItems(XMakeConfigurationDomainType.TOOLCHAIN).count { it.name == "add_linkdirs" })
    }

    fun testDescriptionAvailabilitySamplesStayAligned() {
        val lookup = project.xmakeApi.lookup

        val globalNames = lookup.availableTopLevelCallables(ApiLookupView.DESCRIPTION_GLOBAL_ROOT).map { it.name }.toSet()
        val targetDomainNames = lookup.availableTopLevelCallables(ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET)).map { it.name }.toSet()

        assertTrue("set_project" in globalNames)
        assertFalse("set_project" in targetDomainNames)
        assertTrue("target" in globalNames)
        assertFalse("target" in targetDomainNames)
        assertTrue("add_files" in globalNames)
        assertTrue("add_files" in targetDomainNames)
        assertTrue("ipairs" in targetDomainNames)
        assertTrue("includes" in targetDomainNames)
        assertTrue("add_platformdirs" in targetDomainNames)
        assertTrue("add_toolchaindirs" in targetDomainNames)
        assertFalse("option_end" in globalNames)
    }

    fun testRootOnlyGlobalInterfacesStayOutOfConfigurationDomains() {
        val lookup = project.xmakeApi.lookup

        val globalNames = lookup.availableTopLevelCallables(ApiLookupView.DESCRIPTION_GLOBAL_ROOT).map { it.name }.toSet()
        val targetDomainNames = lookup.availableTopLevelCallables(ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET)).map { it.name }.toSet()
        val rootOnlyGlobalApis = setOf("set_project", "set_config", "add_requires", "add_repositories")

        assertTrue(globalNames.containsAll(rootOnlyGlobalApis))
        assertTrue(targetDomainNames.intersect(rootOnlyGlobalApis).isEmpty())
    }

    fun testTargetConfigurationItemsAreAvailableAsRootScopeTargetDefaults() {
        val lookup = project.xmakeApi.lookup

        val globalNames = lookup.availableTopLevelCallables(ApiLookupView.DESCRIPTION_GLOBAL_ROOT).map { it.name }.toSet()
        val namespaceNames = lookup.availableTopLevelCallables(ApiLookupView(XMakeDomain.Description, XMakeRoot.Namespace(name = "demo"))).map { it.name }.toSet()
        val packageNames = lookup.availableTopLevelCallables(ApiLookupView.configuration(XMakeConfigurationDomainType.PACKAGE)).map { it.name }.toSet()

        assertTrue("add_defines" in globalNames)
        assertTrue("set_kind" in namespaceNames)
        assertTrue("add_files" in globalNames)
        assertFalse("add_files" in packageNames)
    }

    fun testDescriptionBuiltinHelpersAreAvailableInConfigurationDomains() {
        val lookup = project.xmakeApi.lookup

        val packageDomainNames = lookup.availableTopLevelCallables(ApiLookupView.configuration(XMakeConfigurationDomainType.PACKAGE)).map { it.name }.toSet()
        val targetDomainNames = lookup.availableTopLevelCallables(ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET)).map { it.name }.toSet()

        assertTrue("ipairs" in packageDomainNames)
        assertTrue("pairs" in packageDomainNames)
        assertTrue("format" in packageDomainNames)
        assertTrue("print" in packageDomainNames)
        assertTrue("get_config" in targetDomainNames)
        assertTrue("has_package" in targetDomainNames)
        assertTrue("add_moduledirs" in targetDomainNames)
        assertTrue("add_plugindirs" in targetDomainNames)
        assertTrue("set_xmakever" in targetDomainNames)
    }

    fun testAvailableTopLevelCallablesDoNotMixInBuiltinModulePaths() {
        val lookup = project.xmakeApi.lookup

        val globalCallableNames = lookup.availableTopLevelCallables(ApiLookupView.DESCRIPTION_GLOBAL_ROOT).map { it.name }.toSet()
        val globalBuiltinModulePaths = lookup.visibleBuiltinModulePaths(ApiLookupView.DESCRIPTION_GLOBAL_ROOT)

        assertTrue("set_project" in globalCallableNames)
        assertTrue("target" in globalCallableNames)
        assertTrue(globalBuiltinModulePaths.isNotEmpty())
        assertTrue(globalBuiltinModulePaths.none(globalCallableNames::contains))
    }
}
