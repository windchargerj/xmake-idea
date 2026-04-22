package io.xmake.lang.completion

import org.junit.Test

class XMakeCompletionContributorTest : XMakeCompletionTestCase() {

    fun testDescriptionBuiltinApis() = complete(
        "<caret>",
        expected = listOf(
            "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
            "add_requireconfs", "add_requires", "add_toolchaindirs",
            "format", "get_config", "getenv", "has_config", "has_package", "includes", "ipairs",
            "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
            "pairs", "print", "printf",
            "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config", "set_defaultarchs",
            "set_defaultmode", "set_defaultplat", "set_description", "set_project", "set_xmakever",
            "tonumber", "tostring", "type", "unpack"
        ),
        notExpected = listOf("assert", "catch", "cprint", "cprintf")
    )

    fun testDescriptionBuiltinModuleApisForModule() = complete(
        "<caret>",
        expected = listOf("hash", "linuxos", "macos", "math", "os", "path", "string", "table", "winos", "xmake"),
        notExpected = listOf("coroutine", "debug", "io", "utils")
    )

    fun testDescriptionBuiltinModuleApisForComplete() = complete(
        "math.<caret>",
        expected = listOf(
            "abs", "acos", "asin", "atan", "atan2", "ceil", "cos", "cosh", "deg",
            "exp", "floor", "fmod", "frexp", "isinf", "isint", "isnan", "ldexp", "log",
            "log10", "max", "min", "modf", "pow", "rad", "random", "randomseed", "sin",
            "sinh", "sqrt", "tan", "tanh", "tointeger", "type", "ult"
        ),
        notExpected = listOf(
            "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories",
            "add_requireconfs", "add_requires", "add_toolchaindirs",
            "format", "get_config", "getenv", "has_config", "has_package", "includes", "ipairs",
            "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost",
            "pairs", "print", "printf",
            "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config", "set_defaultarchs",
            "set_defaultmode", "set_defaultplat", "set_description", "set_project", "set_xmakever",
            "tonumber", "tostring",/* "type",*/ "unpack",
            "target", "option", "rule", "package", "toolchain", "task",
            "hash", "os", "path", "string", "table", "winos", "xmake",
        )
    )

    fun testDescriptionBuiltinModuleApiForReadOnly() = complete(
        "os.<caret>",
        expected = listOf(
            "arch", "cpuinfo", "curdir", "date", "default_njob", "dirs", "exists", "filedirs", "files",
            "filesize", "getenv", "host", "isdir", "isfile", "mclock", "mtime", "programdir",
            "programfile", "projectdir", "projectfile", "scriptdir", "subarch", "subhost", "term", "time", "tmpdir"
        ),
        notExpected = listOf("cp", "rm", "mv", "exec", "run", "setenv")
    )

    fun testGlobalScopeDomainApi() = complete(
        "<caret>",
        expected = listOf("target", "option", "rule", "package", "toolchain", "task"),
        notExpected = listOf("target_end", "option_end", "rule_end")
    )

    fun testGlobalScopeTargetApi() = complete(
        "<caret>",
        expected = listOf("add_defines", "add_files", "add_includedirs",
            "on_load", "on_build", "on_run", "before_build", "after_build",
        ),
        notExpected = listOf("set_showmenu")
    )

    fun testDomainApi() = complete(
        """
        target("test")
            <caret>
        """.trimIndent(),
        expected = listOf(
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
        ),
        notExpected = listOf("set_showmenu")
    )

    fun testDomainEndApi() = complete(
        """
        target("test")
            <caret>
        """.trimIndent(),
        expected = listOf("target_end", "target", "option", "rule", "package", "toolchain", "task"),
        notExpected = listOf("option_end", "rule_end", "package_end", "toolchain_end", "task_end")
    )

    fun testDomainGlobalApi() = complete(
        """
        package("test")
            <caret>
        """.trimIndent(),
        expected = listOf(
            "add_moduledirs", "add_packagedirs", "add_platformdirs", "add_plugindirs", "add_repositories", "add_requireconfs",
            "add_requires", "add_toolchaindirs", "format", "get_config", "getenv", "has_config", "has_package", "includes", "ipairs",
            "is_arch", "is_config", "is_cross", "is_host", "is_kind", "is_mode", "is_os", "is_plat", "is_subhost", "pairs", "print",
            "printf", "set_allowedarchs", "set_allowedmodes", "set_allowedplats", "set_config", "set_defaultarchs",
            "set_defaultmode", "set_defaultplat", "set_description", "set_project", "set_xmakever", "tonumber", "tostring", "type",
            "unpack"
        ),
        notExpected = listOf(
            "add_cfuncs", "add_cincludes", "add_csnippets", "add_ctypes",
            "add_cxxfuncs", "add_cxxincludes", "add_cxxsnippets", "add_cxxtypes",
            "add_features", "after_check", "before_check", "set_category", "set_showmenu"
        )
    )

    fun testScriptScopeBuiltinModules() = complete(
        """
        target("test")
            on_load(function (target)
                <caret>
            end)
        """.trimIndent(),
        expected = listOf(
            "assert", "catch", "cprint", "cprintf", "dprint", "dprintf", "finally", "find_package", "find_packages", "format",
            "get_config", "has_config", "has_package", "import", "inherit", "ipairs", "irpairs", "is_arch", "is_config", "is_host",
            "is_mode", "is_plat", "is_subhost", "pairs", "print", "printf", "raise", "todisplay", "tonumber", "tostring", "try",
            "type", "unpack", "val", "vformat", "vprint", "vprintf", "wprint"
        ),
        notExpected = listOf("add_files", "set_kind", "add_defines")
    )

    fun testScriptScopeModuleApiFullAccess() = complete(
        """
        target("test")
            on_load(function (target)
                os.<caret>
            end)
        """.trimIndent(),
        expected = listOf(
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
    )

    @Test
    fun testImportCoreSubmodules() = complete(
        """
        target("test")
            on_load(function (target)
                import("core.<caret>")
            end)
        """.trimIndent()
    )

    @Test
    fun testIsolationCoreRequiresImport() = complete(
        """
        target("test")
            on_load(function (target)
                <caret>
            end)
        """.trimIndent(),
        notExpected = listOf("core", "base", "project")
    )

    @Test
    fun testIsolationDescriptionOnlyApis() = complete(
        """
        target("test")
            on_load(function (target)
                <caret>
            end)
        """.trimIndent(),
        notExpected = listOf("add_files", "set_kind", "add_defines")
    )

    @Test
    fun testEdgeInsideComment() = complete(
        "-- comment <caret>",
        notExpected = listOf("set_project", "add_files", "target", "math", "os")
    )

    fun testSpecialCase() = complete(
        """
        target("cmsis1")
            after_build(function (target)
                os.run("ldid S entitlements.plist s", target:targetfile())
            end)
        target_end()
        <caret>
        target("cmsis2")
            add_files("src/*.c")
        """.trimIndent(),
        expected = listOf("hash", "linuxos", "macos", "math", "os", "path", "string", "table", "winos", "xmake"),
        notExpected = listOf("try")
    )
}
