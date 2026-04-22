package io.xmake.lang.codeInsight.reference

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.VisibleSymbolResolver
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.declarations.synthetic.XMakeSyntheticSymbol
import io.xmake.lang.resolution.reference.XMakeReferenceProvider
import org.junit.Assert.assertEquals

/**
 * Plugin-local imported binding resolution coverage.
 *
 * These assertions protect how the IDE models import-created binding carriers,
 * including inherited APIs. They are intentionally
 * separated from [XMakeReferenceResolveTest] because xmake's public
 * documentation does not prescribe a particular PSI resolve target shape.
 */
class XMakeReferenceResolverTest : XMakeTestCase() {

    private val provider = XMakeReferenceProvider()

    fun testImportedExtensionModuleNameResolvesToImportBinding() {
        reference("json") {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                    js<caret>on.encode({})
                end)
            target_end()
            """.trimIndent()
        }.resolvesImportedModule(
            expectedModule = "core.base.json",
            expectedOrigin = XMakeSyntheticSymbol.Origin.IMPORT
        )
    }

    fun testInheritedImportDoesNotExposeSuperModuleBindingReference() {
        reference("_super") {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    _su<caret>per.encode({})
                end)
            target_end()
            """.trimIndent()
        }.doesNotResolve()
    }

    fun testInheritShorthandDoesNotExposeSuperModuleBindingReference() {
        reference("_super") {
            """
            target("test")
                on_load(function (target)
                    inherit("core.base.json")
                    _su<caret>per.encode({})
                end)
            target_end()
            """.trimIndent()
        }.doesNotResolve()
    }

    fun testInheritedImportShortNameDoesNotExposeModuleBindingReference() {
        reference("json") {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    js<caret>on.encode({})
                end)
            target_end()
            """.trimIndent()
        }.doesNotResolve()
    }

    fun testInheritShorthandShortNameDoesNotExposeModuleBindingReference() {
        reference("json") {
            """
            target("test")
                on_load(function (target)
                    inherit("core.base.json")
                    js<caret>on.encode({})
                end)
            target_end()
            """.trimIndent()
        }.doesNotResolve()
    }

    fun testAddImportsModuleNameResolvesToAddImportsBinding() {
        reference("json") {
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    js<caret>on.encode({})
                end)
            target_end()
            """.trimIndent()
        }.resolvesImportedModule(
            expectedModule = "core.base.json",
            expectedOrigin = XMakeSyntheticSymbol.Origin.ADD_IMPORTS
        )
    }

    fun testImportAfterUseDoesNotExposeModuleBindingReference() {
        reference("json") {
            """
            target("test")
                on_load(function (target)
                    js<caret>on.encode({})
                    import("core.base.json")
                end)
            target_end()
            """.trimIndent()
        }.doesNotResolve()
    }

    fun testRootDirImportedLocalModuleNameResolvesToImportBinding() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )

        reference("hello3") {
            """
            target("test")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hel<caret>lo3.greet()
                end)
            target_end()
            """.trimIndent()
        }.resolvesImportedModule(
            expectedModule = "hello3",
            expectedOrigin = XMakeSyntheticSymbol.Origin.IMPORT
        )
    }

    private fun reference(expectedName: String, code: () -> String): ReferenceAssertion {
        val reference = configureAndFindReference(code(), expectedName)
        return ReferenceAssertion(
            expectedName = expectedName,
            reference = reference
        )
    }

    private class ReferenceAssertion(
        private val expectedName: String,
        private val reference: PsiReference,
    ) {
        fun resolvesImportedModule(
            expectedModule: String,
            expectedOrigin: XMakeSyntheticSymbol.Origin,
        ) {
            val identifier = reference.element as? XMakeLuaIdentifier
                ?: error("Expected an XMakeLuaIdentifier reference element")
            val resolved = requireNotNull(reference.resolve())
            val visibleSymbol = VisibleSymbolResolver.resolve(
                identifier,
                io.xmake.lang.declarations.ApiLookupView.fromState(io.xmake.lang.scope.query.XMakeScopeQuery.stateAt(identifier))
            )
            val importedSymbol = (visibleSymbol as? VisibleSymbol.ImportedModule)?.symbol
                ?: error("Expected an imported-module visible symbol, got: $visibleSymbol")

            assertEquals(expectedName, importedSymbol.name)
            assertEquals(expectedModule, importedSymbol.module.identifier)
            assertEquals(expectedOrigin, importedSymbol.origin)
            assertEquals(importedSymbol.declarationElement, resolved)
        }

        fun doesNotResolve() {
            assertNull(reference.resolve())
        }
    }

    private fun configureAndFindReference(code: String, expectedName: String): PsiReference {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val identifier = PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.text == expectedName }
            .minByOrNull { candidate ->
                when {
                    caretOffset in candidate.textRange.startOffset..candidate.textRange.endOffset -> 0
                    caretOffset < candidate.textRange.startOffset -> candidate.textRange.startOffset - caretOffset
                    else -> caretOffset - candidate.textRange.endOffset
                }
            }
        val reference = identifier?.let {
            provider.getReferencesByElement(it, ProcessingContext()).firstOrNull()
        }
        return requireNotNull(reference) { "Reference not found at caret" }
    }
}

