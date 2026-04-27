package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

/**
 * Plugin-local parsing of `import()` calls into the repository's
 * [io.xmake.lang.declarations.import.ImportSpec] model.
 *
 * The exact mapping to declaration-captured aliases and visible binding names
 * combined flag handling is an IDE contract, not a direct xmake-spec assertion.
 */
class ImportCallParserTest : XMakeTestCase() {

    fun testResolveImportScanRootUsesCurrentScriptBlockOnly() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.encode({})
                end)
                on_config(function (target)
                    import("core.base.option")
                    option.showmenu()
                end)
            target_end()
            """.trimIndent()
        )

        val optionIdentifier = PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .first { it.text == "option" }
        val importScanRoot = ImportCallParser.resolveImportScanRoot(optionIdentifier)
        val modulePaths = PsiTreeUtil.findChildrenOfType(importScanRoot, LuaFunctionCall::class.java)
            .asSequence()
            .filter(ImportCallParser::isImportCall)
            .mapNotNull { ImportCallParser.parse(it).getOrNull()?.modulePath }
            .toList()

        assertTrue(importScanRoot is LuaBlock)
        assertEquals(listOf("core.base.option"), modulePaths)
    }

    fun testResolveImportScanRootStaysInsideEmptyHookBody() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    <caret>
                end)
                on_config(function (target)
                    import("core.base.json")
                end)
            target_end()
            """.trimIndent()
        )

        val place = elementAtCaret()
        val importScanRoot = ImportCallParser.resolveImportScanRoot(place)
        val importCalls = PsiTreeUtil.findChildrenOfType(importScanRoot, LuaFunctionCall::class.java)
            .count(ImportCallParser::isImportCall)

        assertTrue(importScanRoot is LuaBlock)
        assertEquals(0, importCalls)
    }

    fun testResolvesLocalVariableAliasFromMatchingAssignmentSlot() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    local a, j = 1, import("core.base.json")
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        val declaration = ImportCallParser.parseDeclaration(call).getOrThrow()
        val importBinding = declaration.toImportBindings().first { it.kind == ImportBindingKind.MODULE_IMPORT }

        assertEquals("core.base.json", spec.modulePath)
        assertEquals("j", declaration.variableAlias)
        assertEquals("json", importBinding.primaryReceiverName)
    }

    fun testResolvesConfiguredAliasAsImportedName() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {alias = "js"})
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        val declaration = ImportCallParser.parseDeclaration(call).getOrThrow()
        val importBinding = declaration.toImportBindings().first { it.kind == ImportBindingKind.MODULE_IMPORT }

        assertEquals("js", spec.alias)
        assertEquals("js", importBinding.primaryReceiverName)
        assertNull(declaration.variableAlias)
    }

    fun testResolvesAnonymousInheritTryAndAlwaysBuildFlags() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true, anonymous = true, try = true, always_build = true})
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        val declaration = ImportCallParser.parseDeclaration(call).getOrThrow()
        val importBinding = declaration.toImportBindings().first { it.kind == ImportBindingKind.MODULE_IMPORT }

        assertTrue(spec.inherit)
        assertTrue(spec.anonymous)
        assertTrue(spec.tryImport)
        assertTrue(spec.alwaysBuild)
        assertNull(importBinding.primaryBoundName)
    }

    fun testResolvesInheritedImportAsReceiverlessBinding() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true, alias = "js"})
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        val declaration = ImportCallParser.parseDeclaration(call).getOrThrow()
        val importBinding = declaration.toImportBindings().first { it.kind == ImportBindingKind.MODULE_IMPORT }

        assertEquals("js", spec.alias)
        assertNull(importBinding.primaryReceiverName)
        assertNull(importBinding.primaryBoundName)
        assertTrue(importBinding.receiverNames.isEmpty())
        assertTrue(importBinding.boundNames.isEmpty())
    }

    fun testResolvesInheritShorthandAsReceiverlessBinding() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    inherit("core.base.json")
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        val declaration = ImportCallParser.parseDeclaration(call).getOrThrow()
        val importBinding = declaration.toImportBindings().first { it.kind == ImportBindingKind.MODULE_IMPORT }

        assertEquals("core.base.json", spec.modulePath)
        assertTrue(spec.inherit)
        assertNull(importBinding.primaryReceiverName)
        assertNull(importBinding.primaryBoundName)
        assertTrue(importBinding.receiverNames.isEmpty())
        assertTrue(importBinding.boundNames.isEmpty())
    }

    fun testResolvesRootDirConfiguration() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("hello3") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        assertEquals("hello3", spec.modulePath)
        assertEquals("modules", spec.rootDir)
    }

    fun testResolvesNoLocalConfiguration() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {nolocal = true})
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first { ImportCallParser.isImportCall(it) && it.text.contains("core.base.json") }

        val spec = ImportCallParser.parse(call).getOrThrow()
        assertTrue(spec.noLocal)
    }

    fun testAcceptsHyphenatedModulePathSegments() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("package.manager.kotlin-native.configurations")
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first(ImportCallParser::isImportCall)

        assertEquals(
            "package.manager.kotlin-native.configurations",
            ImportCallParser.parse(call).getOrThrow().modulePath
        )
    }

    fun testAcceptsLeadingDotRelativeModulePaths() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import(".sibling")
                    inherit("..parent.mod")
                end)
            target_end()
            """.trimIndent()
        )

        val modulePaths = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .asSequence()
            .filter(ImportCallParser::isImportCall)
            .map { ImportCallParser.parse(it).getOrThrow().modulePath }
            .toList()

        assertEquals(listOf(".sibling", "..parent.mod"), modulePaths)
    }

    fun testAcceptsPlainStringModulePathArgument() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("x")
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first(ImportCallParser::isImportCall)

        assertEquals("x", ImportCallParser.parse(call).getOrThrow().modulePath)
    }

    fun testRejectsDynamicAndNestedModulePathArguments() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    local prefix = "core."
                    local variable = "core.base.json"
                    import(prefix .. "x")
                    import({"x"})
                    import(variable)
                end)
            target_end()
            """.trimIndent()
        )

        val results = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .asSequence()
            .filter(ImportCallParser::isImportCall)
            .map { ImportCallParser.parse(it) }
            .toList()

        assertEquals(3, results.size)
        assertTrue(results.all { it.isFailure })
    }

    fun testRejectsEmptyModulePath() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("")
                end)
            target_end()
            """.trimIndent()
        )

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, LuaFunctionCall::class.java)
            .first(ImportCallParser::isImportCall)

        assertTrue(ImportCallParser.parse(call).isFailure)
    }

    private fun elementAtCaret(): PsiElement {
        val caretOffset = myFixture.caretOffset
        return myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
    }
}
