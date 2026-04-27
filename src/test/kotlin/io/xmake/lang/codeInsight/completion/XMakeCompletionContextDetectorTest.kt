package io.xmake.lang.codeInsight.completion

/**
 * Context-detector-assisted completion coverage.
 *
 * These assertions protect plugin-local analysis lifting such as local alias
 * propagation, verified receiver typing, inherited direct API exposure, and
 * local shadowing of imported modules.
 *
 * They intentionally live outside [XMakeCompletionContributorTest] so xmake
 * spec-sensitive completion expectations stay anchored to documented behavior.
 */
class XMakeCompletionContextDetectorTest : XMakeCompletionTestCase() {

    fun testLocalVariableShadowsAddImportsModule() {
        complete {
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    local json = path
                    json.<caret>
                end)
            """.trimIndent()
        }
            .expect("join", "normalize", "absolute")
            .notExpect("decode", "encode", "loadfile", "savefile")
    }

    fun testAliasedAndInferredMemberAccess() {
        complete {
            """
            target("test")
                on_load(function (target)
                    local p = path
                    p.<caret>
                end)
            """.trimIndent()
        }
            .expect("join", "normalize", "absolute")
            .notExpect("add_files", "targetfile", "decode")

        complete {
            """
            target("test")
                on_load(function (target)
                    local t = target
                    t:<caret>
                end)
            """.trimIndent()
        }
            .expect("add", "name", "kind", "targetfile")
            .notExpect("join", "decode", "add_files")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {alias = "j"})
                    j.<caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "loadfile", "savefile")
            .notExpect("join", "add", "targetfile")

        complete {
            """
            target("test")
                on_load(function (target)
                    local json = import("core.base.json", {anonymous = true})
                    json.<caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "loadfile", "savefile")
            .notExpect("join", "add", "targetfile")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    _super.<caret>
                end)
            """.trimIndent()
        }
            .notExpect("decode", "encode", "loadfile", "savefile")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    json.<caret>
                end)
            """.trimIndent()
        }
            .notExpect("decode", "encode", "loadfile", "savefile")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    local marker = <caret>true
                    import("core.base.option", {inherit = true})
                end)
            """.trimIndent()
        }
            .expect("decode", "encode")
            .notExpect("raw_parse", "restore", "show_logo", "show_options", "taskmenu", "taskname")
            .notExpect("json", "_super")

        complete {
            """
            target("test")
                on_load(function (target)
                    local dep = target:dep("core")
                    dep:<caret>
                end)
            """.trimIndent()
        }
            .notExpect("add", "name", "kind", "targetfile", "join", "decode", "add_files")
    }

    fun testIdentifierCompletionIncludesVisibleLocals() {
        complete {
            """
            target("test")
                on_load(function (target)
                    local function build_target()
                    end
                    local foo_value = 1
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("build_target", "foo_value", "target")
    }

    fun testDoesNotCompleteChainedInstanceMethodsWithoutVerifiedReturnType() {
        complete {
            """
            target("test")
                on_load(function (target)
                    target:rule("mode.debug"):<caret>
                end)
            """.trimIndent()
        }
            .notExpect("clone", "name", "join", "encode")
    }

    fun testCanonicalExtensionModuleCompletionDoesNotForceScriptScope() {
        complete {
            """
            target("test")
                core.base.json.<caret>
            target_end()
            """.trimIndent()
        }
            .notExpect("decode", "encode", "loadfile", "savefile")
    }

    fun testBareIdentifierCompletionDoesNotExposeInstanceMethods() {
        complete {
            """
            target("test")
                on_load(function (target)
                    na<caret>
                end)
            """.trimIndent()
        }
            .notExpect("name", "namespace", "kind", "targetfile")
    }
}

