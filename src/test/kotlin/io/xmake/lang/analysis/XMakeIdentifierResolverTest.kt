package io.xmake.lang.analysis

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.analysis.xmake.XMakeIdentifierResolver
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.resolution.ApiResolutionResult
import io.xmake.lang.scope.query.XMakeScopeQuery

/**
 * Feature-level semantic assertions that are directly tied to visible xmake APIs.
 *
 * Plugin-local analysis-model checks live in [LuaSemanticAnalysisModelTest].
 */
class XMakeIdentifierResolverTest : XMakeTestCase() {

    fun testResolvesApiIdentifier() {
        val identifier = identifierAtCaret(
            """
                local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        val resolved = requireNotNull(result as? ApiResolutionResult.Resolved)
        assertEquals("join", resolved.resolution.api.name)
        assertEquals("path", resolved.resolution.api.modulePath)
        assertEquals("path.join", resolved.resolution.api.fullName)
        assertTrue(resolved.resolution.api.isModuleApi)
    }

    fun testResolvesAliasedModuleFunction() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function ()
                        local p = path
                        p.jo<caret>in("src", "main.c")
                    end)
                target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        val resolved = requireNotNull(result as? ApiResolutionResult.Resolved)
        assertEquals("join", resolved.resolution.api.name)
        assertEquals("path", resolved.resolution.api.modulePath)
    }

    fun testLocalShadowedBuiltinModuleDoesNotResolveQualifiedCall() {
        val identifier = identifierAtCaret(
            """
                local path = {}
                path.jo<caret>in("src", "main.c")
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        assertTrue(result is ApiResolutionResult.NotFound)
    }

    fun testLocalShadowedImportedModuleDoesNotResolveImportedModuleCall() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    add_imports("core.base.json")
                    on_load(function ()
                        local json = {}
                        json.enco<caret>de({})
                    end)
                target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        assertTrue(result is ApiResolutionResult.NotFound)
    }

    fun testResolvesLocalImportCaptureModuleDeclaration() {
        myFixture.addFileToProject(
            "modules/dynamic.lua",
            """
            function known()
            end

            missing = make_missing()
            """.trimIndent()
        )
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function ()
                        local d = import("modules.dynamic")
                        d.kno<caret>wn()
                    end)
                target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        val resolved = requireNotNull(result as? ApiResolutionResult.Resolved)

        assertEquals("known", resolved.resolution.api.name)
        assertEquals("modules.dynamic", resolved.resolution.api.modulePath)
    }

    fun testUnknownSurfaceLocalImportCaptureMissingMemberIsConservative() {
        myFixture.addFileToProject(
            "modules/dynamic.lua",
            """
            function known()
            end

            missing = make_missing()
            """.trimIndent()
        )
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function ()
                        local d = import("modules.dynamic")
                        d.no_su<caret>ch()
                    end)
                target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))

        assertFalse(XMakeIdentifierResolver.isUnresolvedApiCall(XMakeApi.getInstance(project), identifier, context))
    }

    fun testLocalImportCaptureKeepsLocalModuleWhenLaterImportUsesSameName() {
        myFixture.addFileToProject(
            "modules/a.lua",
            """
            function known_a()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "modules/b.lua",
            """
            function known_b()
            end
            """.trimIndent()
        )
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function ()
                        local d = import("modules.a")
                        import("modules.b", {alias = "d"})
                        d.known<caret>_a()
                    end)
                target_end()
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        val result = XMakeIdentifierResolver.resolveIdentifier(XMakeApi.getInstance(project), identifier, context)
        val resolved = requireNotNull(result as? ApiResolutionResult.Resolved)

        assertEquals("known_a", resolved.resolution.api.name)
        assertEquals("modules.a", resolved.resolution.api.modulePath)
    }

    fun testDetectsUnresolvedApiCall() {
        val identifier = identifierAtCaret(
            """
                no_su<caret>ch_api("demo")
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        assertTrue(XMakeIdentifierResolver.isUnresolvedApiCall(XMakeApi.getInstance(project), identifier, context))
    }

    fun testMemberCallDoesNotUseBareNameAvailabilityValidation() {
        val identifier = identifierAtCaret(
            """
                target("demo")
                    on_load(function ()
                        local receiver = {}
                        receiver.set_<caret>project("demo")
                    end)
                target_end()
            """.trimIndent()
        )

        assertNull(IdentifierAnalysis.analyze(identifier).validationError)
    }

}
