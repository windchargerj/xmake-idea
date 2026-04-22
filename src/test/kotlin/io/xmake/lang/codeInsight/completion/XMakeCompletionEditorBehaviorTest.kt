package io.xmake.lang.codeInsight.completion

/**
 * Editor behavior tests for xmake.lua completion.
 *
 * These cases cover incomplete input and isolation behavior.
 * They are intentionally separated from spec-sensitive completion assertions.
 */
class XMakeCompletionEditorBehaviorTest : XMakeCompletionTestCase() {

    fun testUnknownMemberAccessStaysIsolated() {
        complete {
            """
            target("test")
                on_load(function (target)
                    unknown.<caret>
                end)
            """.trimIndent()
        }.expectEmpty()
    }

    fun testInvalidInstanceStyleAccessStaysIsolated() {
        complete {
            """
            target("test")
                on_load(function (target)
                    math:<caret>
                end)
            """.trimIndent()
        }.expectEmpty()
    }

    fun testImportPathSuggestsOnlyTopLevelModuleAtEmptyInput() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("<caret>")
                end)
            """.trimIndent()
        }
            .expect("core")
            .notExpect("json", "base", "project")
    }

    fun testImportPathKeepsTopLevelModuleWhileTypingFirstSegment() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("co<caret>")
                end)
            """.trimIndent()
        }
            .expect("core")
            .notExpect("json", "base", "project")
    }

    fun testImportPathSuggestsNestedSegmentAfterQualifiedPrefix() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.ba<caret>")
                end)
            """.trimIndent()
        }
            .expect("base")
            .notExpect("cache", "compress", "project")
    }

    fun testInheritPathSuggestsNestedSegmentAfterQualifiedPrefix() {
        complete {
            """
            target("test")
                on_load(function (target)
                    inherit("core.ba<caret>")
                end)
            """.trimIndent()
        }
            .expect("base")
            .notExpect("cache", "compress", "project")
    }

    fun testImportPathSuggestsLocalCurrentDirectoryModules() {
        myFixture.addFileToProject("modules/hello1.lua", "function greet() end")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("modules.he<caret>")
                end)
            """.trimIndent()
        }
            .expect("hello1")
            .notExpect("base", "project")
    }

    fun testImportPathRespectsRootDirConfiguration() {
        myFixture.addFileToProject("modules/hello3.lua", "function greet() end")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("he<caret>", {rootdir = "modules"})
                end)
            """.trimIndent()
        }
            .expect("hello3")
            .notExpect("core", "base", "project")
    }

    fun testImportedModuleCompletionDoesNotLeakAcrossSiblingHooks() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                end)
                after_load(function (target)
                    json.<caret>
                end)
            """.trimIndent()
        }.expectEmpty()
    }

    fun testImportedModuleCompletionDoesNotAppearBeforeImportStatement() {
        complete {
            """
            target("test")
                on_load(function (target)
                    json.<caret>
                    import("core.base.json")
                end)
            """.trimIndent()
        }.expectEmpty()
    }

    fun testIncompleteIsolatedTargetFunctionSyntaxUsesDomainCompletion() {
        complete {
            """
            target("test", function ()
                <caret>
            """.trimIndent()
        }
            .expect("set_kind", "add_files", "add_defines", "after_build")
            .notExpect("import", "try")
    }

    fun testIncompleteNamespaceFunctionSyntaxUsesNamespaceCompletion() {
        complete {
            """
            namespace("test", function ()
                <caret>
            """.trimIndent()
        }
            .expect("target", "option", "package", "add_requires", "set_project")
            .notExpect("import", "try", "target_end")
    }

    fun testPlainStringLiteralIsolation() {
        complete {
            """
            target("test")
                on_load(function (target)
                    print("hello <caret>")
                end)
            """.trimIndent()
        }.expectEmpty()
    }

    fun testLanguageValueStringDoesNotLeakStatementCompletions() {
        assertNoStatementCompletionLeak(
            complete {
                """
                target("test")
                    set_languages("<caret>")
                """.trimIndent()
            }
        )
    }

    fun testFilePatternStringDoesNotLeakStatementCompletions() {
        assertNoStatementCompletionLeak(
            complete {
                """
                target("test")
                    add_files("src/<caret>.c")
                """.trimIndent()
            }
        )
    }

    fun testImportOptionsTableDoesNotLeakStatementCompletions() {
        assertNoStatementCompletionLeak(
            complete {
                """
                target("test")
                    on_load(function (target)
                        import("core.base.json", {<caret>})
                    end)
                """.trimIndent()
            }
        )
    }

    fun testTableValueAllowsMemberCompletion() {
        complete {
            """
            target("test")
                on_load(function (target)
                    local opts = { outdir = path.<caret> }
                end)
            """.trimIndent()
        }
            .expect("join", "normalize", "absolute")
            .notExpect("add_files", "target_end", "import")
    }

    fun testTableValueAllowsIdentifierCompletion() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {rootdir = pa<caret>})
                end)
            """.trimIndent()
        }
            .expect("path")
            .notExpect("add_files", "target_end", "import")
    }

    fun testCommentIsolation() {
        complete { "-- comment <caret>" }.expectEmpty()
    }

    fun testRootCompletionAfterDomainBlock() {
        complete {
            """
            option("demo")
                set_default(false)
            option_end()
            <caret>
            target("app")
                add_files("src/*.c")
            """.trimIndent()
        }
            .expect("math", "os", "target", "option", "package")
            .notExpect("try")
    }

    fun testRootCompletionAfterScriptBlock() {
        complete {
            """
            target("cmsis1")
                after_build(function (target)
                    os.run("ldid S entitlements.plist s", target:targetfile())
                end)
            target_end()
            <caret>
            target("cmsis2")
                add_files("src/*.c")
            """.trimIndent()
        }
            .expect("target", "os")
            .notExpect("import", "target_end", "try")
    }

    fun testRootCompletionAfterShortScriptBlock() {
        complete {
            """
            target("cmsis1")
                on_load(function ()
                end)
            target_end()
            <caret>
            """.trimIndent()
        }
            .expect("math", "os", "target", "option", "package")
            .notExpect("try")
    }

    fun testRootCompletionAfterIsolatedTargetFunctionSyntax() {
        complete {
            """
            target("cmsis1", function ()
            end)
            <caret>
            """.trimIndent()
        }
            .expect("math", "os", "target", "option", "package")
            .notExpect("try")
    }

    private fun assertNoStatementCompletionLeak(result: XMakeCompletionTestCase.CompletionResult) {
        result.notExpect(
            "target",
            "option",
            "package",
            "namespace",
            "set_kind",
            "add_files",
            "add_defines",
            "after_build",
            "on_load",
            "import",
            "target_end",
            "option_end",
            "try",
        )
    }
}

