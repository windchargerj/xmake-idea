package io.xmake.lang.analysis

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.analysis.lua.LuaTypeInference

/**
 * Validates the plugin-local type inference model.
 *
 * These tests exercise repo-local dataflow and [XMakeType] mapping on top of xmake-backed
 * symbols. They are not direct proof of xmake specification behavior.
 */
class LocalTypeInferenceTest : XMakeTestCase() {

    fun testInfersAliasedModuleType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local p = path
                    p.join("src", "main.c")
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isModule("path")
    }

    fun testDoesNotInferShadowedBuiltinModuleType() {
        inferType {
            """
            local path = {}
            local copy = <caret>path
            """.trimIndent()
        }.isUnknown()
    }

    fun testInfersImportReturnAliasModuleType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local json = import("core.base.json")
                    local copy = <caret>json
                end)
            target_end()
            """.trimIndent()
        }.isModule("core.base.json")
    }

    fun testInfersAnonymousImportReturnAliasModuleType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local json = import("core.base.json", {anonymous = true})
                    local copy = <caret>json
                end)
            target_end()
            """.trimIndent()
        }.isModule("core.base.json")
    }

    fun testDoesNotInferDirectImportTypeForUnknownLocalModuleSurface() {
        myFixture.addFileToProject(
            "modules/dynamic.lua",
            """
            greet = make_greet()
            """.trimIndent()
        )

        inferType {
            """
            target("test")
                on_load(function (target)
                    local dynamic = import("modules.dynamic")
                    local copy = <caret>dynamic
                end)
            target_end()
            """.trimIndent()
        }.isUnknownType()
    }

    fun testInfersAliasedVerifiedHookParameterType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local t = target
                    t:name()
                    local copy = <caret>t
                end)
            target_end()
            """.trimIndent()
        }.isInstance("target")
    }

    fun testInfersChainedAliasType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local p = path
                    local q = p
                    local copy = <caret>q
                end)
            target_end()
            """.trimIndent()
        }.isModule("path")
    }

    fun testInfersLocalAliasTypeWhenShadowingAddImportsModule() {
        inferType {
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    local json = path
                    local copy = <caret>json
                end)
            target_end()
            """.trimIndent()
        }.isModule("path")
    }

    fun testDoesNotInferFunctionReturnedModuleType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local function get_path()
                        return path
                    end
                    local p = get_path()
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferFunctionReturnWhenBranchesReturnDifferentTypes() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local json = import("core.base.json")
                    local function pick(flag)
                        if flag then
                            return path
                        end
                        return json
                    end
                    local p = pick(true)
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferFunctionReturnAfterNilEarlyReturn() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local function get_path(flag)
                        if flag then
                            return nil
                        end
                        return path
                    end
                    local p = get_path(true)
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferOuterFunctionReturnFromNestedFunction() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local function get_path()
                        local function nested()
                            return path
                        end
                    end
                    local p = get_path()
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferFunctionReturnedVerifiedHookParameterType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local function get_target()
                        return target
                    end
                    local t = get_target()
                    local copy = <caret>t
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferComplexInitializerExpressionType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local json = import("core.base.json")
                    local p = path or json
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferIncompleteInitializerType() {
        inferType {
            """
            target("test")
                on_load(function (target)
                    local p =
                    local copy = <caret>p
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testInfersOptionHookParameterType() {
        inferType {
            """
            option("feature")
                on_check(function (opt)
                    local copy = <caret>opt
                end)
            option_end()
            """.trimIndent()
        }.isInstance("option")
    }

    fun testInfersPackageHookParameterType() {
        inferType {
            """
            package("zlib")
                on_install(function (pkg)
                    local copy = <caret>pkg
                end)
            package_end()
            """.trimIndent()
        }.isInstance("package")
    }

    fun testInfersToolchainHookParameterType() {
        inferType {
            """
            toolchain("myclang")
                on_load(function (tc)
                    local copy = <caret>tc
                end)
            toolchain_end()
            """.trimIndent()
        }.isInstance("toolchain")
    }

    fun testInfersTargetFileHookParameterType() {
        inferType {
            """
            target("demo")
                on_build_file(function (t, sourcefile, opt)
                    local copy = <caret>t
                end)
            target_end()
            """.trimIndent()
        }.isInstance("target")
    }

    fun testInfersPackageSourceHookParameterType() {
        inferType {
            """
            package("zlib")
                on_source(function (pkg)
                    local copy = <caret>pkg
                end)
            package_end()
            """.trimIndent()
        }.isInstance("package")
    }

    fun testDoesNotInferNonPrimaryTargetHookParameterType() {
        inferType {
            """
            target("demo")
                on_build_file(function (t, sourcefile, opt)
                    local copy = <caret>sourcefile
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferTargetFileHookOptionsParameterType() {
        inferType {
            """
            target("demo")
                on_build_file(function (t, sourcefile, opt)
                    local copy = <caret>opt
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferRuleBuildcmdFileBatchcmdsParameterType() {
        inferType {
            """
            rule("demo")
                on_buildcmd_file(function (target, batchcmds, sourcefile, opt)
                    local copy = <caret>batchcmds
                end)
            rule_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferPackageDownloadOptParameterType() {
        inferType {
            """
            package("zlib")
                on_download(function (pkg, opt)
                    local copy = <caret>opt
                end)
            package_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferTargetDepReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    local dep = target:dep("core")
                    local copy = <caret>dep
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferTargetPkgReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    local pkg = target:pkg("zlib")
                    local copy = <caret>pkg
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferOptionDepReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            option("feature")
                on_check(function (opt)
                    local dep = opt:dep("small")
                    local copy = <caret>dep
                end)
            option_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferPackageDepReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            package("demo")
                on_install(function (pkg)
                    local dep = pkg:dep("zlib")
                    local copy = <caret>dep
                end)
            package_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferTargetRuleReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    local r = target:rule("mode.debug")
                    local copy = <caret>r
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferTargetRuleCloneReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    local cloned = target:rule("mode.debug"):clone()
                    local copy = <caret>cloned
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferNameGetterReturnTypeWithoutVerifiedMemberReturn() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    local name = target:name()
                    local copy = <caret>name
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testInfersImportedModuleShortNameType() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    local copy = <caret>json
                end)
            target_end()
            """.trimIndent()
        }.isModule("core.base.json")
    }

    fun testInfersImportedModuleAliasType() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {alias = "j"})
                    local copy = <caret>j
                end)
            target_end()
            """.trimIndent()
        }.isModule("core.base.json")
    }

    fun testDoesNotInferHiddenModuleTypeForInheritedImport() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    local copy = <caret>json
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferSuperModuleTypeForInheritedImport() {
        inferType {
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    local copy = <caret>_super
                end)
            target_end()
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferImplicitGlobalAssignmentInsideNestedFunction() {
        inferType {
            """
            local function assign()
                leaked = path
            end
            local copy = <caret>leaked
            """.trimIndent()
        }.isUnknown()
    }

    fun testDoesNotInferImplicitGlobalAssignmentInsideCondition() {
        inferType {
            """
            if true then
                leaked = path
            end
            local copy = <caret>leaked
            """.trimIndent()
        }.isUnknown()
    }

    fun testInfersTopLevelImplicitGlobalAssignmentType() {
        inferType {
            """
            leaked = path
            local copy = <caret>leaked
            """.trimIndent()
        }.isModule("path", ApiLookupView.DESCRIPTION_GLOBAL_ROOT)
    }

    private fun inferType(code: () -> String): InferredTypeResult {
        val declaration = identifierAtCaret(code())
        return InferredTypeResult(LuaTypeInference.inferType(declaration))
    }

    private class InferredTypeResult(private val inferred: XMakeType?) {
        fun isModule(
            expectedModule: String,
            context: ApiLookupView = ApiLookupView.SCRIPT_GLOBAL_ROOT
        ): InferredTypeResult {
            assertEquals(XMakeType.Module(expectedModule, context), inferred)
            return this
        }

        fun isInstance(expectedTypeName: String): InferredTypeResult {
            assertEquals(XMakeType.Instance(expectedTypeName), inferred)
            return this
        }

        fun isUnknown() {
            assertNull(inferred)
        }

        fun isUnknownType() {
            assertEquals(XMakeType.Unknown, inferred)
        }
    }

}
