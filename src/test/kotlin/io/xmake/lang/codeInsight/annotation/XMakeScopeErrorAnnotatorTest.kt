package io.xmake.lang.codeInsight.annotation

import com.intellij.lang.annotation.HighlightSeverity
import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute

class XMakeScopeErrorAnnotatorTest : XMakeAnnotatorTestCase() {

    fun testScriptDomainAddFilesError() = highlight("""
        target("demo")
            on_load(function(target)
                <error>add_files</error>("src/*.c")
            end)
        target_end()
    """.trimIndent())

    fun testScriptDomainSetProjectError() = highlight("""
        target("demo")
            after_build(function(target)
                <error>set_project</error>("cmsis")
            end)
        target_end()
    """.trimIndent())

    fun testPackageXMakeRootOnlyApiError() = highlight("""
        package("zlib")
            <error>set_project</error>("cmsis")
        package_end()
    """.trimIndent())

    fun testPackageDescriptionBuiltinHelpersAreValid() = highlight("""
        package("zlib")
            for _, name in ipairs({"z", "lib"}) do
                print(name)
            end
        package_end()
    """.trimIndent())

    fun testGlobalScopeValidApis() = highlight("""
        add_defines("DEBUG")
        set_project("myproject")
        target("demo")
            set_kind("binary")
            add_files("src/*.c")
        target_end()
    """.trimIndent())

    fun testTargetScopeValidApis() = highlight("""
        target("demo")
            set_kind("binary")
            add_files("src/*.c")
            add_defines("DEBUG")
        target_end()
    """.trimIndent())

    fun testMissingTargetNameReportsEntryErrorWithoutCascadingToTargetEnd() = highlight("""
        <error descr="target() requires a name.">target</error>()
            set_kind("binary")
        target_end()
    """.trimIndent())

    fun testEmptyTargetNameReportsEntryErrorWithoutCascadingToTargetEnd() = highlight("""
        <error descr="target() name must not be empty.">target</error>("")
            set_kind("binary")
        target_end()
    """.trimIndent())

    fun testConditionalTargetScopeAllowsTargetEnd() = highlight("""
        option("a")
        option_end()

        if is_arch("arm") then
            target("b")
                set_languages("c11")
            target_end()
        end
    """.trimIndent())

    fun testConditionalTargetScopePersistsAfterBlockEnd() = highlight("""
        if is_arch("arm") then
            target("b")
                set_languages("c11")
        end
        <error>option_end</error>()
        target_end()
    """.trimIndent())

    fun testIsolatedTargetFunctionSyntaxValidApis() = highlight("""
        target("demo", function ()
            set_kind("binary")
            add_files("src/*.c")
        end)
    """.trimIndent())

    fun testMismatchedDomainEndDoesNotTerminateCurrentScope() = highlight("""
        target("demo")
            <error>option_end</error>()
            set_kind("binary")
        target_end()
    """.trimIndent())

    fun testNamespaceScopeAllowsNamespaceEnd() = highlight("""
        namespace("test")
            target("hello")
                add_files("src/*.c")
            target_end()
        namespace_end()
    """.trimIndent())

    fun testNamespaceRootAllowsTargetSharedApis() = highlight("""
        namespace("test")
            add_defines("NS1_ROOT")
            target("hello")
                set_kind("binary")
            target_end()
        namespace_end()
    """.trimIndent())

    fun testOptionScopeValidApis() = highlight("""
        option("test")
            set_default("value")
            add_deps("other")
        option_end()
    """.trimIndent())

    fun testPackageScopeValidApis() = highlight("""
        package("zlib")
            set_description("zlib")
        package_end()
    """.trimIndent())

    fun testScriptDomainImportIsAllowed() = highlight("""
        target("cmsis1")
            after_build(function (target)
                import("core.base.json", {alias = "j"})
                j.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testGotoMissingLabelError() = highlight("""
        goto <error descr="Label 'done' is not visible in this block or function">done</error>
    """.trimIndent())

    fun testGotoCannotJumpIntoLocalScope() = highlight("""
        goto <error descr="goto 'done' jumps into the domain of local 'value'">done</error>
        local value = 1
        ::done::
    """.trimIndent())

    fun testGotoCannotSeeOuterLabelAcrossFunctionBoundary() = highlight("""
        ::done::
        local function worker()
            goto <error descr="Label 'done' is not visible in this block or function">done</error>
        end
    """.trimIndent())

    fun testNestedLabelCannotShadowVisibleOuterLabel() = highlight("""
        ::done::
        do
            ::<error descr="Label 'done' conflicts with visible label 'done'">done</error>::
        end
    """.trimIndent())

    fun testScopeErrorSuppressesSemanticHighlighting() {
        assertErrorAtCaretWithoutSemanticHighlighting(
            """
                target("demo")
                    after_build(function(target)
                        add_fi<caret>les("src/*.c")
                    end)
                target_end()
            """.trimIndent(),
            XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
            XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL
        )
    }

    private fun assertErrorAtCaretWithoutSemanticHighlighting(
        code: String,
        vararg forbidden: com.intellij.openapi.editor.colors.TextAttributesKey
    ) {
        myFixture.configureByText("xmake.lua", code)
        val offset = myFixture.caretOffset
        val overlapping = myFixture.doHighlighting().filter { info ->
            info.startOffset <= offset && offset < info.endOffset
        }

        assertTrue("Expected an error highlight at caret", overlapping.any { it.severity == HighlightSeverity.ERROR })
        forbidden.forEach { key ->
            assertFalse(
                "Did not expect semantic highlight ${key.externalName} together with domain error. Actual: ${overlapping.map { it.forcedTextAttributesKey?.externalName }}",
                overlapping.any { it.forcedTextAttributesKey == key }
            )
        }
    }
}
