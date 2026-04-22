package io.xmake.lang.codeInsight.inspection

/**
 * Analysis-assisted unresolved-symbol coverage.
 *
 * These checks protect plugin-local analysis lifting such as unknown-receiver
 * isolation, alias propagation, verified return-value propagation, import
 * inheritance, hidden inherited module bindings, and add_imports reachability.
 *
 * They are kept separate from the spec-sensitive baseline suite so
 * analysis-engine refactors can move without being confused with lexical/spec
 * regressions.
 */
class XMakeUnresolvedSymbolInspectionAnalysisTest : XMakeInspectionTestCase() {

    fun testInstanceMethodIsNotUnresolved() = highlight("""
        target("test")
            on_load(function (target)
                target:add("defines", "DEBUG")
            end)
        target_end()
    """.trimIndent())

    fun testAliasedModuleFunctionIsNotUnresolved() = highlight("""
        target("test")
            on_load(function (target)
                local p = path
                local joined = p.join("src", "main.c")
            end)
        target_end()
    """.trimIndent())

    fun testAliasedInstanceMethodIsNotUnresolved() = highlight("""
        target("test")
            on_load(function (target)
                local t = target
                t:add("defines", "DEBUG")
            end)
        target_end()
    """.trimIndent())

    fun testFunctionReturnedModuleIsNotUnresolved() = highlight("""
        target("test")
            on_load(function (target)
                local function get_path()
                    return path
                end
                local p = get_path()
                local joined = p.join("src", "main.c")
            end)
        target_end()
    """.trimIndent())

    fun testFunctionReturnedInstanceMethodIsNotUnresolved() = highlight("""
        target("test")
            on_load(function (target)
                local function get_target()
                    return target
                end
                local t = get_target()
                t:add("defines", "DEBUG")
            end)
        target_end()
    """.trimIndent())

    fun testOptionHookInstanceMethodIsNotUnresolved() = highlight("""
        option("feature")
            on_check(function (opt)
                if opt:enabled() then
                    opt:enable(false)
                end
            end)
        option_end()
    """.trimIndent())

    fun testPackageHookInstanceMethodIsNotUnresolved() = highlight("""
        package("zlib")
            on_install(function (pkg)
                local dir = pkg:installdir()
            end)
        package_end()
    """.trimIndent())

    fun testToolchainHookInstanceMethodIsNotUnresolved() = highlight("""
        toolchain("myclang")
            on_load(function (tc)
                tc:load_cross_toolchain()
            end)
        toolchain_end()
    """.trimIndent())

    fun testPackageSourceHookInstanceMethodIsNotUnresolved() = highlight("""
        package("zlib")
            on_source(function (pkg)
                local dir = pkg:installdir()
            end)
        package_end()
    """.trimIndent())

    fun testTargetDepReturnInstanceMethodIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local dep = target:dep("core")
                local file = dep:targetfile()
            end)
        target_end()
    """.trimIndent())

    fun testTargetPkgReturnInstanceMethodIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local pkg = target:pkg("zlib")
                local dir = pkg:installdir()
            end)
        target_end()
    """.trimIndent())

    fun testOptionDepReturnInstanceMethodIsNotUnresolved() = highlight("""
        option("feature")
            on_check(function (opt)
                local dep = opt:dep("small")
                if dep:enabled() then
                    dep:enable(false)
                end
            end)
        option_end()
    """.trimIndent())

    fun testPackageDepReturnInstanceMethodIsNotUnresolved() = highlight("""
        package("demo")
            on_install(function (pkg)
                local dep = pkg:dep("zlib")
                local dir = dep:installdir()
            end)
        package_end()
    """.trimIndent())

    fun testTargetRuleReturnInstanceMethodIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local rule = target:rule("mode.debug")
                local cloned = rule:clone()
                local name = cloned:name()
            end)
        target_end()
    """.trimIndent())

    fun testBareScriptInstanceMethodIsReportedAsUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local value = <error descr="Unresolved function 'name'">name</error>()
            end)
        target_end()
    """.trimIndent())

    fun testImportedExtensionModuleFunctionIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                import("core.base.json")
                local encoded = json.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testAnonymousImportReturnAliasModuleFunctionIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local json_api = import("core.base.json", {anonymous = true})
                local encoded = json_api.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritedExtensionModuleFunctionIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                import("core.base.json", {inherit = true})
                local encoded = encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritShorthandExtensionModuleFunctionIsNotUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                inherit("core.base.json")
                local encoded = encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritedImportSuperModuleBindingIsUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                import("core.base.json", {inherit = true})
                local encoded = <error descr="Unresolved variable '_super'">_super</error>.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritShorthandSuperModuleBindingIsUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                inherit("core.base.json")
                local encoded = <error descr="Unresolved variable '_super'">_super</error>.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritedImportShortNameDoesNotExposeModuleBinding() = highlight("""
        target("demo")
            on_load(function (target)
                import("core.base.json", {inherit = true})
                local encoded = <error descr="Unresolved variable 'json'">json</error>.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testInheritShorthandShortNameDoesNotExposeModuleBinding() = highlight("""
        target("demo")
            on_load(function (target)
                inherit("core.base.json")
                local encoded = <error descr="Unresolved variable 'json'">json</error>.encode({})
            end)
        target_end()
    """.trimIndent())

    fun testAddImportsModuleMemberIsNotUnresolved() = highlight("""
        target("demo")
            add_imports("core.base.json")
            on_load(function (target)
                local encoded = json.encode({})
            end)
        target_end()
    """.trimIndent())

fun testAddImportsDoesNotExposeModuleInDescriptionDomain() = highlight("""
        target("demo")
            add_imports("core.base.json")
            local encoded = <error descr="Unresolved variable 'json'">json</error>.encode({})
        target_end()
    """.trimIndent())

    fun testRootDirImportedLocalModuleFunctionIsNotUnresolved() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )

        highlight("""
            target("demo")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    local message = hello3.greet()
                end)
            target_end()
        """.trimIndent())
    }

    fun testUninheritedExtensionModuleFunctionIsUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local encoded = <error descr="Unresolved function 'encode'">encode</error>({})
            end)
        target_end()
    """.trimIndent())

    fun testUnimportedExtensionModuleFunctionIsUnresolved() = highlight("""
        target("demo")
            on_load(function (target)
                local encoded = <error descr="Unresolved variable 'json'">json</error>.encode({})
            end)
        target_end()
    """.trimIndent())
}

