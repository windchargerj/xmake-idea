package io.xmake.lang.codeInsight.completion

/**
 * Spec-sensitive integration tests for xmake.lua completion.
 *
 * This suite only keeps expectations that are intended to remain valid as the
 * xmake-aligned completion baseline during refactors and TDD.
 *
 * Editor behavior and isolation behavior live in
 * [XMakeCompletionEditorBehaviorTest].
 *
 * Insertion behavior lives in [XMakeCompletionEditorInsertionTest].
 *
 * Domain model:
 * - Description domain uses set_xxx/add_xxx style configuration APIs.
 * - Script domain is inside function bodies passed to on_xxx/before_xxx/after_xxx hooks.
 *
 * Availability rules:
 * - Root description APIs and structural entry functions are available from the description domain.
 * - Target APIs are also valid at description roots as tree-scoped defaults for later targets.
 * - Option/Rule/Package/Toolchain APIs stay within their own configuration domains.
 * - Description builtin helper/query APIs are visible throughout the description domain.
 * - Project root mutators such as add_requires stay at the description root.
 *
 * Analysis-assisted aliasing and verified receiver/member cases live in
 * [XMakeCompletionContextDetectorTest].
 */
class XMakeCompletionContributorTest : XMakeCompletionTestCase() {

    // Description domain
    fun testDescriptionDomainBuiltins() {
        complete { "<caret>" }
            .expect(
                // Global builtins
                "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
                "add_requireconfs", "add_requires", "add_toolchaindirs",
                "format", "get_config", "getenv", "has_config", "has_package", "includes", "ipairs",
                "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
                "pairs", "print", "printf",
                "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config", "set_defaultarchs",
                "set_defaultmode", "set_defaultplat", "set_description", "set_project", "set_xmakever",
                // Target defaults valid from the root description scope
                "add_defines", "add_files", "add_includedirs", "on_load", "before_build", "after_build",
                "tonumber", "tostring", "type", "unpack",
                // Lua keywords
                "function", "local", "if", "for", "while", "return",
                // Global-visible modules
                "hash", "linuxos", "macos", "math", "os", "path", "string", "table", "winos", "xmake",
                // Domain entry functions
                "namespace", "target", "option", "rule", "package", "toolchain", "task"
            )
            .notExpect(
                "assert", "catch", "cprint", "cprintf",
                "coroutine", "debug", "io", "utils",
                "target_end", "option_end", "rule_end",
                "set_showmenu", "set_urls", "set_bindir",
                "on_fetch", "on_check"
            )
    }

    fun testDescriptionDomainModules() {
        complete { "math.<caret>" }
            .expect(
                "abs", "acos", "asin", "atan", "atan2", "ceil", "cos", "cosh", "deg",
                "exp", "floor", "fmod", "frexp", "isinf", "isint", "isnan", "ldexp", "log",
                "log10", "max", "min", "modf", "pow", "rad", "random", "randomseed", "sin",
                "sinh", "sqrt", "tan", "tanh", "tointeger", "type", "ult"
            )
            .notExpect(
                // Global builtins
                "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
                "add_requireconfs", "add_requires", "add_toolchaindirs",
                "format", "get_config", "getenv", "has_config", "has_package", "includes", "ipairs",
                "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
                "pairs", "print", "printf",
                "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config", "set_defaultarchs",
                "set_defaultmode", "set_defaultplat", "set_description", "set_project", "set_xmakever",
                "tonumber", "tostring", "unpack",
                // Domain entry functions
                "target", "option", "rule", "package", "toolchain", "task",
                // Other modules
                "hash", "os", "path", "string", "table", "winos", "xmake",
            )

        complete { "os.<caret>" }
            .expect(
                "arch", "cpuinfo", "curdir", "date", "default_njob", "dirs", "exists", "filedirs", "files",
                "filesize", "getenv", "host", "isdir", "isfile", "mclock", "mtime", "programdir",
                "programfile", "projectdir", "projectfile", "scriptdir", "subarch", "subhost", "term", "time", "tmpdir"
            )
            .notExpect("cp", "rm", "mv", "exec", "run", "setenv")
    }

    fun testDescriptionDomainPrefixFiltering() {
        complete { "se<caret>" }
            .expect("set_project", "set_description", "set_xmakever")
            .notExpect("add_files", "print", "target", "math")

        complete { "math.s<caret>" }
            .expect("sin", "sinh", "sqrt")
    }

    // Configuration domain
    fun testConfigurationDomainTargetApis() {
        complete {
            """
            target("test")
                <caret>
            """.trimIndent()
        }
            .expect(
                "add_arflags", "add_asflags", "add_cflags", "add_cleanfiles", "add_configfiles",
                "add_cuflags", "add_cugencodes", "add_culdflags", "add_cxflags", "add_cxxflags",
                "add_dcflags", "add_defines", "add_deps", "add_extrafiles", "add_fcflags",
                "add_filegroups", "add_files", "add_forceincludes", "add_frameworkdirs", "add_frameworks",
                "add_gcflags", "add_headerfiles", "add_imports", "add_includedirs", "add_installfiles",
                "add_kcflags", "add_languages", "add_ldflags", "add_linkdirs", "add_linkgroups",
                "add_linkorders", "add_links", "add_mflags", "add_mrcflags", "add_mxflags",
                "add_mxxflags", "add_ncflags", "add_options", "add_packages", "add_pcflags",
                "add_rcflags", "add_rpathdirs", "add_rules", "add_runenvs", "add_scflags",
                "add_shflags", "add_sysincludedirs", "add_syslinks", "add_tests", "add_toolchains",
                "add_undefines", "add_values", "add_vectorexts", "add_zcflags", "after_build",
                "after_build_file", "after_build_files", "after_buildcmd", "after_buildcmd_file",
                "after_buildcmd_files", "after_clean", "after_config", "after_install", "after_installcmd",
                "after_link", "after_linkcmd", "after_load", "after_package", "after_prepare",
                "after_prepare_file", "after_prepare_files", "after_preparecmd", "after_preparecmd_file",
                "after_preparecmd_files", "after_run", "after_test", "after_uninstall", "after_uninstallcmd",
                "before_build", "before_build_file", "before_build_files", "before_buildcmd",
                "before_buildcmd_file", "before_buildcmd_files", "before_clean", "before_config",
                "before_install", "before_installcmd", "before_link", "before_linkcmd", "before_package",
                "before_prepare", "before_prepare_file", "before_prepare_files", "before_preparecmd",
                "before_preparecmd_file", "before_preparecmd_files", "before_run", "before_test",
                "before_uninstall", "before_uninstallcmd", "del_files", "on_build", "on_build_file",
                "on_build_files", "on_buildcmd", "on_buildcmd_file", "on_buildcmd_files", "on_clean",
                "on_config", "on_install", "on_installcmd", "on_link", "on_linkcmd", "on_load",
                "on_package", "on_prepare", "on_prepare_file", "on_prepare_files", "on_preparecmd",
                "on_preparecmd_file", "on_preparecmd_files", "on_run", "on_test", "on_uninstall",
                "on_uninstallcmd", "remove_configfiles", "remove_extrafiles", "remove_files",
                "remove_headerfiles", "remove_installfiles", "set_arch", "set_autogendir", "set_basename",
                "set_configdir", "set_configvar", "set_default", "set_dependir", "set_enabled",
                "set_encodings", "set_exceptions", "set_extension", "set_filename", "set_fpmodels",
                "set_group", "set_installdir", "set_kind", "set_languages", "set_license",
                "set_objectdir", "set_optimize", "set_options", "set_pcheader", "set_pcxxheader",
                "set_plat", "set_pmheader", "set_pmxxheader", "set_policy", "set_prefixdir",
                "set_prefixname", "set_rules", "set_runargs", "set_rundir", "set_runenv",
                "set_runtimes", "set_strip", "set_suffixname", "set_symbols", "set_targetdir",
                "set_toolchains", "set_toolset", "set_values", "set_version", "set_warnings"
            )
            .notExpect("set_showmenu")
    }

    fun testConfigurationDomainTargetDescriptionBuiltins() {
        complete {
            """
            target("test")
                <caret>
            """.trimIndent()
        }
            .expect(
                "format", "get_config", "getenv", "has_config", "has_package", "ipairs",
                "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
                "pairs", "print", "printf", "tonumber", "tostring", "type", "unpack"
            )
            .notExpect(
                "set_project", "set_description", "set_config", "set_allowedarchs", "set_allowedmodes", "set_allowedplats",
                "set_defaultarchs", "set_defaultmode", "set_defaultplat", "set_xmakever",
                "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
                "add_requireconfs", "add_requires", "add_toolchaindirs", "includes"
            )
    }

    fun testConfigurationDomainTargetTransitions() {
        complete {
            """
            target("test")
                <caret>
            """.trimIndent()
        }
            .expect("target_end")
            .notExpect("target", "option", "rule", "package", "toolchain", "task")
            .notExpect("option_end", "rule_end", "package_end", "toolchain_end", "task_end")
    }

    fun testConditionalConfigurationDomainTargetTransitions() {
        complete {
            """
            if is_arch("arm") then
                target("test")
                    <caret>
                target_end()
            end
            """.trimIndent()
        }
            .expect("target_end")
            .notExpect("target", "option", "rule", "package", "toolchain", "task")
            .notExpect("option_end", "rule_end", "package_end", "toolchain_end", "task_end")
    }

    fun testConditionalTargetScopePersistsAfterBlockEnd() {
        complete {
            """
            if is_arch("arm") then
                target("test")
                    set_kind("binary")
            end
                <caret>
            target_end()
            """.trimIndent()
        }
            .expect("add_defines", "add_files", "set_kind", "target_end")
            .notExpect("set_project", "add_requires", "namespace_end")
    }

    fun testIsolatedTargetFunctionSyntaxUsesDomainCompletion() {
        complete {
            """
            target("test", function ()
                <caret>
            end)
            """.trimIndent()
        }
            .expect("set_kind", "add_files", "add_defines", "after_build")
            .notExpect("import", "try")
    }

    fun testNamespaceScopeCompletionOffersNamespaceEnd() {
        complete {
            """
            namespace("test")
                <caret>
            namespace_end()
            """.trimIndent()
        }
            .expect("target", "option", "package", "namespace_end", "add_requires", "set_project")
            .notExpect("target_end", "option_end", "package_end", "rule_end")
    }

    fun testNamespaceScopeCompletionOffersTargetDefaults() {
        complete {
            """
            namespace("test")
                <caret>
            namespace_end()
            """.trimIndent()
        }
            .expect("target", "namespace_end", "add_defines", "add_files", "on_load")
            .notExpect("target_end", "option_end", "rule_end", "on_check", "on_fetch")
    }

    fun testConfigurationDomainPackageExposesDescriptionBuiltinHelpers() {
        complete {
            """
            package("test")
                <caret>
            """.trimIndent()
        }
            .expect(
                "add_imports", "set_description", "on_load", "package_end",
                "format", "get_config", "getenv", "has_config", "has_package", "ipairs",
                "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
                "pairs", "print", "printf", "tonumber", "tostring", "type", "unpack"
            )
            .notExpect(
                "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
                "add_requireconfs", "add_requires", "add_toolchaindirs", "includes",
                "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config",
                "set_defaultarchs", "set_defaultmode", "set_defaultplat", "set_project", "set_xmakever",
                "add_cfuncs", "add_cincludes", "add_csnippets", "add_ctypes",
                "add_cxxfuncs", "add_cxxincludes", "add_cxxsnippets", "add_cxxtypes",
                "add_features", "after_check", "before_check", "set_category", "set_showmenu"
            )
    }

    fun testPackageScopeDoesNotExposeUndeclaredInstallcmdApisWhenMissingFromExportedApiJson() {
        complete {
            """
            package("test")
                on_installc<caret>
            """.trimIndent()
        }
            .notExpect(
                "on_installcmd",
                "before_installcmd",
                "after_installcmd",
                "before_uninstallcmd",
                "after_uninstallcmd"
            )
    }

    fun testConfigurationDomainOptionApis() {
        complete {
            """
            option("demo")
                <caret>
            option("demo")
            """.trimIndent()
        }
            .expect("set_default", "set_description", "set_showmenu", "add_cfuncs", "add_features", "before_check", "on_check", "after_check")
            .notExpect("set_project", "add_requires", "set_urls", "on_install", "set_bindir")
    }

    // Script domain
    fun testScriptDomainBuiltins() {
        complete {
            """
            target("test")
                on_load(function (target)
                    <caret>
                end)
            """.trimIndent()
        }
            .expect(
                "assert", "catch", "cprint", "cprintf", "dprint", "dprintf", "finally", "find_package", "find_packages", "format",
                "get_config", "has_config", "has_package", "import", "inherit", "ipairs", "irpairs", "is_arch", "is_config", "is_host",
                "is_mode", "is_plat", "is_subhost", "pairs", "print", "printf", "raise", "todisplay", "tonumber", "tostring", "try",
                "type", "unpack", "val", "vformat", "vprint", "vprintf", "wprint",
                "function", "local", "if", "for", "while", "return", "end"
            )
            .notExpect("add_files", "set_kind", "add_defines")
    }

    fun testLuaKeywordPrefixFiltering() {
        complete { "f<caret>" }
            .expect("function", "for", "false")
            .notExpect("target", "on_build")

        complete { "lo<caret>" }
            .expect("local")
            .notExpect("target")

        complete {
            """
            target("test")
                on_load(function (target)
                    e<caret>
                end)
            """.trimIndent()
        }
            .expect("end", "else", "elseif")
            .notExpect("enabled", "encode")
    }

    fun testScriptDomainOsModule() {
        complete {
            """
            target("test")
                on_load(function (target)
                    os.<caret>
                end)
            """.trimIndent()
        }
            .expect(
                "addenv", "addenvp", "addenvs", "arch", "args", "argv", "atexit", "cd", "cp", "cpuinfo",
                "curdir", "date", "default_njob", "dirs", "emptydir", "exec", "execv", "exists", "exit",
                "features", "filedirs", "files", "filesize", "fscase", "getenv", "getenvs", "getpid",
                "getwinsize", "host", "iorun", "iorunv", "is_arch", "is_host", "is_subarch", "is_subhost",
                "isdir", "isexec", "isfile", "islink", "isroot", "joinenvs", "ln", "match", "mclock",
                "meminfo", "mkdir", "mtime", "mv", "nuldev", "pbcopy", "pbpaste", "programdir", "programfile",
                "projectdir", "projectfile", "raise", "readlink", "rm", "rmdir", "run", "runv", "scriptdir",
                "setenv", "setenvp", "setenvs", "shell", "sleep", "strerror", "subarch", "subhost", "syserror",
                "term", "time", "tmpdir", "tmpfile", "touch", "trycp", "trymv", "tryrm", "vcp", "vexec",
                "vexecv", "workingdir", "xmakever"
            )
    }

    // Script domain: imports and hook objects
    fun testScriptDomainExtensionModules() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                    json.<caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "is_marked_as_array", "loadfile", "mark_as_array", "savefile")
            .notExpect("join", "add", "targetfile")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "loadfile", "savefile")
            .notExpect("json", "_super")

        complete {
            """
            target("test")
                on_load(function (target)
                    inherit("core.base.json")
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "loadfile", "savefile")
            .notExpect("json", "_super")

        complete {
            """
            target("test")
                on_load(function (target)
                    core.base.<caret>
                end)
            """.trimIndent()
        }
            .notExpect("base64", "bit", "global", "json", "option", "process", "semver", "task")

        complete {
            """
            target("test")
                on_load(function (target)
                    json.<caret>
                end)
            """.trimIndent()
        }
            .notExpect("decode", "encode")

        complete {
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    json.<caret>
                end)
            """.trimIndent()
        }
            .expect("decode", "encode", "loadfile", "savefile")
            .notExpect("join", "add", "targetfile")
    }

    fun testScriptDomainHashsetExtensionModule() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.hashset")
                    hashset.<caret>
                end)
            """.trimIndent()
        }
            .expect("clear", "from", "has", "new", "of", "to_array")
            .notExpect("decode", "encode", "loadfile", "savefile")
    }

    fun testScriptDomainRootDirImportedLocalModules() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )

        complete {
            """
            target("test")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hello3.<caret>
                end)
            """.trimIndent()
        }
            .expect("greet")
            .notExpect("decode", "encode", "join")
    }

    fun testScriptDomainCompletesVerifiedHookReceiverObjects() {
        complete {
            """
            target("test")
                on_load(function (target)
                    target:<caret>
                end)
            """.trimIndent()
        }
            .expect("add", "get", "set", "name", "kind", "targetfile")
            .notExpect("path", "json", "math", "add_files")

        complete {
            """
            option("feature")
                on_check(function (opt)
                    opt:<caret>
                end)
            option_end()
            """.trimIndent()
        }
            .expect("enabled", "enable", "get", "set", "add", "dep")
            .notExpect("join", "targetfile", "add_files")

        complete {
            """
            package("zlib")
                on_install(function (pkg)
                    pkg:<caret>
                end)
            package_end()
            """.trimIndent()
        }
            .expect("installdir", "config", "add", "set", "dep", "version")
            .notExpect("join", "enabled", "add_files")

        complete {
            """
            toolchain("myclang")
                on_load(function (tc)
                    tc:<caret>
                end)
            toolchain_end()
            """.trimIndent()
        }
            .expect("load_cross_toolchain", "add", "set", "is_arch")
            .notExpect("join", "enabled", "add_files")
    }

    fun testScriptDomainMemberPrefixCompletesVerifiedHookReceiver() {
        complete {
            """
            target("test")
                on_load(function (target)
                    target:na<caret>
                end)
            """.trimIndent()
        }
            .expect("name")
            .notExpect("kind", "set", "join")
    }

    fun testScriptDomainImportedModulesStayUnavailableWithoutImport() {
        complete {
            """
            target("test")
                on_load(function (target)
                    <caret>
                end)
            """.trimIndent()
        }
            .notExpect("json", "semver", "process")
    }

    fun testScriptDomainSuggestsBuiltinModuleNamesAtTopLevel() {
        complete {
            """
            target("test")
                on_load(function (target)
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("os", "path", "io")
            .notExpect("json", "semver", "process")
    }

    fun testScriptDomainSuggestsImportedReceiverNamesAtTopLevel() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("json")
            .notExpect("semver", "_super")

        complete {
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    <caret>
                end)
            """.trimIndent()
        }
            .expect("json")
            .notExpect("semver", "_super")
    }

    fun testImportedReceiverNamesStayScopedToCurrentScriptDomain() {
        complete {
            """
            target("test")
                add_imports("core.base.json")
                <caret>
                on_load(function (target)
                end)
            """.trimIndent()
        }
            .notExpect("json")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                end)
                on_config(function (target)
                    <caret>
                end)
            """.trimIndent()
        }
            .notExpect("json")
    }

    // Import path
    fun testImportPathScope() {
        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.<caret>")
                end)
            """.trimIndent()
        }
            .expect("base", "cache", "compress", "language", "package", "platform", "project", "sandbox", "theme", "tool", "ui")
            .notExpect("json", "global", "options")

        complete {
            """
            target("test")
                on_load(function (target)
                    import("core.base.missing.<caret>")
                end)
            """.trimIndent()
        }
            .notExpect("base", "package", "project", "tool")
    }

    fun testAddImportsPathScope() {
        complete {
            """
            target("test")
                add_imports("core.<caret>")
            """.trimIndent()
        }
            .expect("base", "cache", "compress", "language", "package", "platform", "project", "sandbox", "theme", "tool", "ui")
            .notExpect("json", "global", "options")

        complete {
            """
            target("test")
                add_imports("core.base.json", "core.<caret>")
            """.trimIndent()
        }
            .expect("base", "cache", "compress", "language", "package", "platform", "project", "sandbox", "theme", "tool", "ui")
            .notExpect("json", "global", "options")

        complete {
            """
            target("test")
                add_imports({"core.<caret>"})
            """.trimIndent()
        }
            .expect("base", "cache", "compress", "language", "package", "platform", "project", "sandbox", "theme", "tool", "ui")
            .notExpect("json", "global", "options")
    }

}

