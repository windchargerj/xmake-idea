package io.xmake.lang.declarations

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.declarations.resolver.DefaultTypeResolver
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.utils.info.XMakeApis
import io.xmake.utils.info.XMakeInfoManager

/**
 * Doc-backed API availability and domain assertions.
 *
 * These checks stay close to xmake's documented phase/domain rules. Script-local
 * import lookup across hook bodies lives in [ApiImportLookupTest].
 */
class ApiDomainAvailabilityTest : XMakeTestCase() {

    fun testDomainEndOnlyAvailableInMatchingDomain() {
        val api = project.xmakeApi
        val optionEnd = api.lookup.findByName("option_end").first()

        assertTrue(optionEnd.isAvailableIn(ApiLookupView.configuration(XMakeConfigurationDomainType.OPTION)))
        assertFalse(optionEnd.isAvailableIn(ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET)))
    }

    fun testScriptOnlyModuleReferenceDoesNotResolveInDescriptionDomain() {
        val api = project.xmakeApi
        val selector = QualifiedApiSelector.ModuleFunction(modulePath = "io", functionName = "read")

        assertNull(api.lookup.findApiByQualifiedSelector(selector, ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
        assertNotNull(api.lookup.findApiByQualifiedSelector(selector, ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testTypeResolverDoesNotExposeScriptOnlyModuleInDescriptionDomain() {
        val resolver = DefaultTypeResolver(project)

        assertNull(resolver.resolveType("io", ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
        // io is NOT in DESCRIPTION_IMPLICIT_MODULES (script-phase only), so io.read is not valid in the global description domain
        assertFalse(resolver.isModulePath("io.read", ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
        assertNotNull(resolver.resolveType("io", ApiLookupView.SCRIPT_GLOBAL_ROOT))
        assertTrue(resolver.isModulePath("io.read", ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testTypeResolverDoesNotInferBareXMakeInstanceWithoutLuaBinding() {
        val resolver = DefaultTypeResolver(project)

        assertNull(resolver.resolveType("target", ApiLookupView.SCRIPT_GLOBAL_ROOT))
        assertNull(resolver.resolveType("package", ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testModuleTypePreservesExplicitApiLookupView() {
        val context = ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET, XMakeRoot.Namespace())

        val module = XMakeType.Module("path", context)

        assertEquals(context, module.context)
    }

    fun testApiLookupViewWithDomainPreservesRoot() {
        val context = ApiLookupView.configuration(
            XMakeConfigurationDomainType.TARGET,
            XMakeRoot.Namespace(name = "demo")
        )

        val scriptContext = context.withDomain(XMakeDomain.Script)

        assertEquals(XMakeDomain.Script, scriptContext.domain)
        assertEquals(XMakeDomain.Script, scriptContext.domain)
        assertEquals(context.root, scriptContext.root)
    }

    fun testAvailabilityPolicyKeepsRootOnlyGlobalInterfaceAtDescriptionRoot() {
        val api = project.xmakeApi.lookup.findByName("add_requires").first()

        assertEquals(
            listOf("description domain (global root)"),
            ApiAvailabilityPolicy.describeAvailabilities(listOf(api))
        )
    }

    fun testAvailabilityPolicyExposesDescriptionBuiltinHelpersInConfigurationDomains() {
        val api = project.xmakeApi.lookup.findByName("ipairs").first()

        assertTrue(api.isAvailableIn(ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
        assertTrue(api.isAvailableIn(ApiLookupView.configuration(XMakeConfigurationDomainType.TARGET)))
        assertEquals(
            listOf(
                "description domain (global root)",
                "configuration domain (target)",
                "configuration domain (option)",
                "configuration domain (rule)",
                "configuration domain (task)",
                "configuration domain (toolchain)",
                "configuration domain (package)"
            ),
            ApiAvailabilityPolicy.describeAvailabilities(listOf(api))
        )
    }

    fun testBareScriptInstanceMethodIsNotResolvedAsUnqualifiedApi() {
        val api = project.xmakeApi

        assertNull(api.findUnqualifiedApi("name", ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testInheritedApiVisibleOnlyInScriptDomain() {
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

        val encodeIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "encode" }
        val api = project.xmakeApi

        assertNotNull(api.findUnqualifiedApi("encode", ApiLookupView.SCRIPT_GLOBAL_ROOT, file, encodeIdentifier))
        assertNull(api.findUnqualifiedApi("encode", ApiLookupView.DESCRIPTION_GLOBAL_ROOT, file, encodeIdentifier))
    }

    fun testInheritedApiDoesNotLeakAcrossSiblingHooks() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.option", {inherit = true})
                end)
                on_config(function (target)
                    show_logo("demo")
                end)
            target_end()
            """.trimIndent()
        )

        val showLogoIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "show_logo" }
        val api = project.xmakeApi

        assertNull(api.findUnqualifiedApi("show_logo", ApiLookupView.SCRIPT_GLOBAL_ROOT, file, showLogoIdentifier))
    }

    fun testInheritedApiRequiresFileContextForImportAwareLookup() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.option", {inherit = true})
                    show_logo("demo")
                end)
            target_end()
            """.trimIndent()
        )

        val showLogoIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "show_logo" }
        val api = project.xmakeApi

        assertNull(api.findUnqualifiedApi("show_logo", ApiLookupView.SCRIPT_GLOBAL_ROOT))
        assertNull(api.findUnqualifiedApi("show_logo", ApiLookupView.SCRIPT_GLOBAL_ROOT, place = showLogoIdentifier))
        assertNotNull(api.findUnqualifiedApi("show_logo", ApiLookupView.SCRIPT_GLOBAL_ROOT, file, showLogoIdentifier))
    }

    fun testLookupRefreshesAfterApiReload() {
        val lookup = project.xmakeApi.lookup
        assertTrue(lookup.findByName("set_project").isNotEmpty())

        XMakeInfoManager.getInstance(project).xmakeInfo.apis = XMakeApis(
            descriptionBuiltinApis = listOf("fresh_global")
        )
        ApiService.getInstance(project).reload()

        assertTrue(lookup.findByName("fresh_global").isNotEmpty())
        assertTrue(lookup.findByName("set_project").isEmpty())
        assertNotNull(project.xmakeApi.findUnqualifiedApi("fresh_global", ApiLookupView.DESCRIPTION_GLOBAL_ROOT))
    }

    fun testTypeResolverRefreshesAfterApiReload() {
        val resolver = DefaultTypeResolver(project)
        assertNotNull(resolver.resolveType("os", ApiLookupView.SCRIPT_GLOBAL_ROOT))

        XMakeInfoManager.getInstance(project).xmakeInfo.apis = XMakeApis(
            scriptBuiltinModuleApis = listOf("freshmod.call")
        )
        ApiService.getInstance(project).reload()

        assertNull(resolver.resolveType("os", ApiLookupView.SCRIPT_GLOBAL_ROOT))
        val refreshedType = resolver.resolveType("freshmod", ApiLookupView.SCRIPT_GLOBAL_ROOT) as? XMakeType.Module
        assertNotNull(refreshedType)
        assertEquals("freshmod", refreshedType?.path)
        assertTrue(resolver.isModulePath("freshmod.call", ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testTypeResolverKeepsImportedRootModulePathPlaceSensitive() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core")
                end)
                on_config(function (target)
                    core.base.json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val encodeIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "encode" }
        val resolver = DefaultTypeResolver(project)

        assertFalse(resolver.isModulePath("core.base.json", ApiLookupView.SCRIPT_GLOBAL_ROOT, file, encodeIdentifier))
    }

    fun testApiCountRefreshesAfterApiReload() {
        val service = ApiService.getInstance(project)
        service.getApiCount()

        XMakeInfoManager.getInstance(project).xmakeInfo.apis = XMakeApis(
            descriptionBuiltinApis = listOf("fresh_global"),
            scriptBuiltinApis = listOf("fresh_builtin"),
            scriptBuiltinModuleApis = listOf("freshmod.call")
        )
        service.reload()

        assertEquals(3, service.getApiCount())
    }

    private fun configure(code: String): io.xmake.lang.syntax.psi.XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as io.xmake.lang.syntax.psi.XMakeLuaFile
    }
}
