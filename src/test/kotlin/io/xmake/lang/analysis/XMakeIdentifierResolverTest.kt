package io.xmake.lang.analysis

import io.xmake.lang.XMakeTestCase
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

    fun testDetectsUnresolvedApiCall() {
        val identifier = identifierAtCaret(
            """
                no_su<caret>ch_api("demo")
            """.trimIndent()
        )

        val context = ApiLookupView.fromState(XMakeScopeQuery.stateAt(identifier))
        assertTrue(XMakeIdentifierResolver.isUnresolvedApiCall(XMakeApi.getInstance(project), identifier, context))
    }

}
