package io.xmake.lang.editor.highlighting.semantic

import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute

/**
 * Analysis-assisted highlighting coverage.
 *
 * These assertions protect plugin-local type/receiver propagation, local alias
 * tracking, inherited direct APIs, and verified return-object methods. They
 * live outside the stable highlighting baseline.
 */
class XMakeSemanticHighlightingAnalysisTest : XMakeSemanticHighlightingTestCase() {

    fun testInheritedApiFunctionHighlighting() {
        highlighting {
            """
                target("demo")
                    after_build(function (target)
                        import("core.base.json", {inherit = true})
                        enco<caret>de({})
                    end)
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL)
    }

    fun testAliasedModuleFunctionHighlightingOnMember() {
        highlighting {
            """
                target("test")
                    on_load(function (target)
                        local p = path
                        p.jo<caret>in("src", "main.c")
                    end)
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
    }

    fun testAliasedVerifiedHookReceiverHasInstanceMethodHighlighting() {
        highlighting {
            """
                target("test")
                    on_load(function (target)
                        local t = target
                        t:a<caret>dd("defines", "DEBUG")
                    end)
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testFunctionReturnedModuleMemberStaysConservative() {
        highlighting {
            """
                target("test")
                    on_load(function (target)
                        local function get_path()
                            return path
                        end
                        local p = get_path()
                        p.jo<caret>in("src", "main.c")
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
    }

    fun testUnknownFunctionReturnedHookReceiverHasNoInstanceMethodHighlighting() {
        highlighting {
            """
                target("test")
                    on_load(function (target)
                        local function get_target()
                            return target
                        end
                        local t = get_target()
                        t:a<caret>dd("defines", "DEBUG")
                    end)
                target_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownTargetDepReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    on_load(function (target)
                        local dep = target:dep("core")
                        dep:target<caret>file()
                    end)
                target_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownTargetPkgReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    on_load(function (target)
                        local pkg = target:pkg("zlib")
                        pkg:install<caret>dir()
                    end)
                target_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownOptionDepReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                option("feature")
                    on_check(function (opt)
                        local dep = opt:dep("small")
                        if dep:enab<caret>led() then
                        end
                    end)
                option_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownPackageDepReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                package("demo")
                    on_install(function (pkg)
                        local dep = pkg:dep("zlib")
                        dep:install<caret>dir()
                    end)
                package_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownTargetRuleReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    on_load(function (target)
                        local rule = target:rule("mode.debug")
                        rule:clo<caret>ne()
                    end)
                target_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testUnknownTargetRuleCloneReturnHasNoInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    on_load(function (target)
                        local cloned = target:rule("mode.debug"):clone()
                        cloned:na<caret>me()
                    end)
                target_end()
            """.trimIndent()
        }.notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }
}
