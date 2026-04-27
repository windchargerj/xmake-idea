package io.xmake.lang.editor.highlighting.semantic

import io.xmake.lang.editor.highlighting.XMakeLuaTextAttribute

/**
 * Baseline semantic highlighting tests for stable user-visible categories.
 *
 * This suite covers direct description/script/configuration-domain highlighting plus stable
 * lexical categories such as locals, parameters, labels, and imported module
 * calls that do not rely on deeper semantic-model lifting.
 *
 * Incomplete-input behavior is intentionally conservative in the current
 * precise-only architecture and does not live in a separate recovery suite.
 * Analysis-assisted alias/return-type/verified receiver cases live in
 * [XMakeSemanticHighlightingAnalysisTest].
 */
class XMakeSemanticHighlightingTest : XMakeSemanticHighlightingTestCase() {

    fun testConfigurationDomainEntryHighlighting() {
        highlighting {
            """
                tar<caret>get("demo")
                    set_kind("binary")
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API)
    }

    fun testConfigurationDomainEndHighlightingUsesConfigurationDomainPalette() {
        highlighting {
            """
                target("demo")
                    set_kind("binary")
                target_<caret>end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API)
    }

    fun testNamespaceEntryHighlightingUsesConfigurationDomainPalette() {
        highlighting {
            """
                name<caret>space("demo")
                    target("hello")
                        set_kind("binary")
                    target_end()
                namespace_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API)
    }

    fun testNamespaceEndHighlightingUsesConfigurationDomainPalette() {
        highlighting {
            """
                namespace("demo")
                    target("hello")
                        set_kind("binary")
                    target_end()
                namespace_<caret>end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API)
    }

    fun testHookParameterDoesNotReuseConfigurationDomainHighlighting() {
        highlighting {
            """
                target("demo")
                    after_build(function (ta<caret>rget)
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_CONFIGURATION_DOMAIN_API)
    }

    fun testDescriptionApiHighlightingOnPlainCall() {
        highlighting {
            """
                target("demo")
                    after_bu<caret>ild(function (target)
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL)
    }

    fun testScriptApiHighlightingOnPlainCall() {
        highlighting {
            """
                target("demo")
                    after_build(function (target)
                        im<caret>port("core.base.json", {alias = "j"})
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL)
    }

    fun testDescriptionBuiltinFunctionHighlightingOnPlainCall() {
        highlighting {
            """
                target("demo")
                    for _, source in ip<caret>airs({"src/main.c"}) do
                        add_files(source)
                    end
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_BUILTIN_FUNCTION_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_BUILTIN_FUNCTION_CALL)
    }

    fun testLocalVariableUsageHighlighting() {
        highlighting {
            """
                local value = 1
                local copy = va<caret>lue
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE)
    }

    fun testParameterUsageHighlighting() {
        highlighting {
            """
                local function greet(name)
                    local copy = na<caret>me
                end
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_PARAMETER)
    }

    fun testGenericForVariableUsageHighlighting() {
        highlighting {
            """
                for _, name in ipairs({"pthread", "dl"}) do
                    local copy = na<caret>me
                end
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_LOCAL_VARIABLE)
    }

    fun testFunctionDeclarationHighlighting() {
        highlighting {
            """
                local function gre<caret>et(name)
                    return name
                end
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION)
    }

    fun testQualifiedFunctionDeclarationHighlighting() {
        highlighting {
            """
                local mod = {}
                function mod.fo<caret>o()
                end
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_DECLARATION)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD)
    }

    fun testLocalFunctionCallHighlighting() {
        highlighting {
            """
                local function greet(name)
                    return name
                end
                gre<caret>et("codex")
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
            .notExpect(
                XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
                XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL
            )
    }

    fun testModuleFunctionHighlightingOnMember() {
        highlighting {
            """
                local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
            .notExpect(
                XMakeLuaTextAttribute.XMAKE_LUA_DESCRIPTION_API_CALL,
                XMakeLuaTextAttribute.XMAKE_LUA_SCRIPT_API_CALL,
                XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD
            )
    }

    fun testVerifiedHookReceiverHasInstanceMethodHighlightingOnMember() {
        highlighting {
            """
                target("test")
                    on_load(function (target)
                        target:a<caret>dd("defines", "DEBUG")
                    end)
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testImportedAliasModuleFunctionHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    after_build(function (target)
                        import("core.base.json", {alias = "j"})
                        j.enco<caret>de({})
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testAddImportsModuleFunctionHighlightingOnMember() {
        highlighting {
            """
                target("demo")
                    add_imports("core.base.json")
                    after_build(function (target)
                        json.enco<caret>de({})
                    end)
                target_end()
            """.trimIndent()
        }
            .expect(XMakeLuaTextAttribute.XMAKE_LUA_FUNCTION_CALL)
            .notExpect(XMakeLuaTextAttribute.XMAKE_LUA_INSTANCE_METHOD)
    }

    fun testImportAliasTableKeyHighlighting() {
        highlighting {
            """
                target("demo")
                    after_build(function (target)
                        import("core.base.json", {ali<caret>as = "j"})
                    end)
                target_end()
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_KEY)
    }

    fun testTableFieldHighlighting() {
        highlighting {
            """
                local payload = {name = "demo"}
                local copy = payload.na<caret>me
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_TABLE_FIELD)
    }

    fun testLabelHighlighting() {
        highlighting {
            """
                ::do<caret>ne::
                goto done
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_LABEL)
    }

    fun testGotoLabelReferenceHighlighting() {
        highlighting {
            """
                goto do<caret>ne
                ::done::
            """.trimIndent()
        }.expect(XMakeLuaTextAttribute.XMAKE_LUA_LABEL)
    }
}
