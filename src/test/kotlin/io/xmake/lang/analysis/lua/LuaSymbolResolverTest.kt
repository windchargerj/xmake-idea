package io.xmake.lang.analysis.lua

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbol

/**
 * Plugin-local visible-symbol synthesis coverage.
 *
 * This suite protects how the IDE turns imports and inherited APIs into
 * synthetic resolution targets for later semantic layers.
 */
class LuaSymbolResolverTest : XMakeTestCase() {

    fun testResolvesImportedModuleAsSyntheticVisibleSymbol() {
        val identifier = configureAndFind(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json<caret>.encode({})
                end)
            target_end()
            """.trimIndent(),
            "json"
        )

        val resolved = VisibleSymbolResolver.resolve(identifier, ApiLookupView.SCRIPT_GLOBAL_ROOT) as? VisibleSymbol.ImportedModule
        assertNotNull(resolved)
        assertEquals(XMakeSyntheticSymbol.Origin.IMPORT, resolved?.symbol?.origin)
        assertEquals("json", resolved?.symbol?.name)
        assertEquals(
            XMakeType.Module("core.base.json", ApiLookupView.SCRIPT_GLOBAL_ROOT),
            resolved?.inferredType
        )
    }

    fun testResolvesInheritedApiAsSyntheticVisibleSymbol() {
        val identifier = configureAndFind(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    enco<caret>de({})
                end)
            target_end()
            """.trimIndent(),
            "encode"
        )

        val resolved = VisibleSymbolResolver.resolve(identifier, ApiLookupView.SCRIPT_GLOBAL_ROOT) as? VisibleSymbol.InheritedApi
        assertNotNull(resolved)
        assertEquals(XMakeSyntheticSymbol.Origin.INHERIT, resolved?.symbol?.origin)
        assertEquals("encode", resolved?.symbol?.name)
    }

    fun testHookParameterDoesNotLeakToFollowingTopLevelTargetCall() {
        val identifier = configureAndFind(
            """
            target("one")
                after_build(function (target)
                    print(target:name())
                end)
            tar<caret>get("two")
                set_kind("binary")
            target_end()
            """.trimIndent(),
            "target"
        )

        val context = ApiLookupContext.forIdentifier(identifier)
        val resolved = VisibleSymbolResolver.resolve(identifier, context) as? VisibleSymbol.BuiltinApi
        assertNotNull(resolved)
        assertEquals("target", resolved?.api?.name)
    }

    fun testAddImportsDoesNotExposeSyntheticModuleInDescriptionDomain() {
        val identifier = configureAndFind(
            """
            target("demo")
                add_imports("core.base.json")
                js<caret>on.encode({})
            target_end()
            """.trimIndent(),
            "json"
        )

        val context = ApiLookupContext.forIdentifier(identifier)
        assertNull(VisibleSymbolResolver.resolve(identifier, context))
    }

    fun testResolvesBuiltinModuleAsVisibleSymbol() {
        val identifier = configureAndFind(
            """
            local copy = pa<caret>th
            """.trimIndent(),
            "path"
        )

        val resolved = VisibleSymbolResolver.resolve(identifier, ApiLookupView.DESCRIPTION_GLOBAL_ROOT) as? VisibleSymbol.BuiltinModule

        assertNotNull(resolved)
        assertEquals("path", resolved?.modulePath)
    }

    private fun configureAndFind(code: String, expectedName: String): XMakeLuaIdentifier {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        return PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.text == expectedName }
            .minByOrNull { identifier ->
                when {
                    caretOffset in identifier.textRange.startOffset..identifier.textRange.endOffset -> 0
                    caretOffset < identifier.textRange.startOffset -> identifier.textRange.startOffset - caretOffset
                    else -> caretOffset - identifier.textRange.endOffset
                }
            }
            ?: error("No identifier near caret")
    }
}
