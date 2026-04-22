package io.xmake.lang.declarations.import

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

/**
 * Plugin-local import binding extraction coverage.
 *
 * These assertions protect IDE-side modeling such as local assignment capture
 * from `import()` return values and place-sensitive lookup within the current
 * script body.
 */
class ImportBindingViewTest : XMakeTestCase() {

    fun testTracksLocalImportReturnAlias() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    local optmod = import("core.base.option")
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("core.base.option", imports.resolveBoundModule("optmod")?.identifier)
        assertEquals("core.base.option", imports.resolveBoundModule("option")?.identifier)
        assertEquals("core.base.option", imports.resolveReceiverModule("option")?.identifier)
        assertNull(imports.resolveReceiverModule("optmod"))
    }

    fun testAnonymousImportTracksOnlyLocalReturnAlias() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    local j = import("core.base.option", {anonymous = true})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("core.base.option", imports.resolveBoundModule("j")?.identifier)
        assertNull(imports.resolveBoundModule("option"))
        assertNull(imports.resolveReceiverModule("j"))
    }

    fun testResolveBindingTargetUsesLatestVisibleDeclarationForDuplicateBinding() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    import("core.base.json")
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val calls = PsiTreeUtil.findChildrenOfType(file, io.xmake.lang.syntax.psi.lua.LuaFunctionCall::class.java)
            .filter { it.firstChild?.text == "import" }
            .sortedBy { it.textOffset }

        val targets = requireNotNull(project.xmakeApi.imports.viewAt(file).resolveBindingTargets("json"))

        assertEquals(calls.last().textOffset, targets.primary.declarationElement?.textOffset)
        assertEquals(listOf(calls.first().textOffset), targets.shadowed.mapNotNull { it.declarationElement?.textOffset })
        assertTrue(targets.conflicts.isEmpty())
    }

    fun testResolveBindingTargetsKeepsConflictingModulesForSameBoundName() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    import("core.base.option", {alias = "json"})
                    json.show_menu()
                end)
            target_end()
            """.trimIndent()
        )

        val targets = requireNotNull(project.xmakeApi.imports.viewAt(file).resolveBindingTargets("json"))

        assertEquals("core.base.option", targets.primary.module.identifier)
        assertTrue(targets.shadowed.isEmpty())
        assertEquals(listOf("core.base.json"), targets.conflicts.map { it.module.identifier })
    }

    fun testIgnoresUnsupportedTaskAddImports() {
        val file = configure(
            """
            task("demo")
                add_imports("core.base.json")
                on_run(function ()
                    json.encode({})
                end)
            task_end()
            """.trimIndent()
        )

        val jsonIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "json" }

        assertNull(project.xmakeApi.imports.viewAt(file, jsonIdentifier).resolveBoundModule("json"))
    }

    fun testTracksSupportedAddImportsBindingAndOrigin() {
        val file = configure(
            """
            target("demo")
                add_imports("core.base.json")
                on_load(function (target)
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)
        val binding = imports.resolveReceiverBindingTargets("json")?.primary

        assertEquals("core.base.json", imports.resolveBoundModule("json")?.identifier)
        assertEquals("core.base.json", imports.resolveReceiverModule("json")?.identifier)
        assertEquals(ImportBindingOrigin.ADD_IMPORTS, binding?.origin)
    }

    fun testTracksSupportedAddImportsArrayBindings() {
        val file = configure(
            """
            target("demo")
                add_imports({"core.base.json", "core.base.option"})
                on_load(function (target)
                    json.encode({})
                    option.showmenu()
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("core.base.json", imports.resolveBoundModule("json")?.identifier)
        assertEquals("core.base.option", imports.resolveBoundModule("option")?.identifier)
        assertEquals(ImportBindingOrigin.ADD_IMPORTS, imports.resolveReceiverBindingTargets("json")?.primary?.origin)
        assertEquals(ImportBindingOrigin.ADD_IMPORTS, imports.resolveReceiverBindingTargets("option")?.primary?.origin)
    }

    fun testTracksSupportedAddImportsAcrossOptionRuleAndPackageDomains() {
        val cases = listOf(
            """
            option("demo")
                add_imports("core.base.json")
                on_check(function (option)
                    json.encode({})
                end)
            option_end()
            """.trimIndent(),
            """
            rule("demo")
                add_imports("core.base.json")
                on_load(function (target)
                    json.encode({})
                end)
            rule_end()
            """.trimIndent(),
            """
            package("demo")
                add_imports("core.base.json")
                on_load(function (package)
                    json.encode({})
                end)
            package_end()
            """.trimIndent()
        )

        cases.forEach { code ->
            val file = configure(code)
            val jsonIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
                .first { it.text == "json" }

            val imports = project.xmakeApi.imports.viewAt(file, jsonIdentifier)
            val binding = imports.resolveReceiverBindingTargets("json")?.primary

            assertEquals("core.base.json", imports.resolveBoundModule("json")?.identifier)
            assertEquals("core.base.json", imports.resolveReceiverModule("json")?.identifier)
            assertEquals(ImportBindingOrigin.ADD_IMPORTS, binding?.origin)
        }
    }

    fun testParseUsesCurrentScriptDomainWhenPlaceProvided() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.encode({})
                end)
                on_config(function (target)
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .filter { it.text == "json" }
            .sortedBy { it.textOffset }

        val onLoadImports = project.xmakeApi.imports.viewAt(file, identifiers.first())
        val onConfigImports = project.xmakeApi.imports.viewAt(file, identifiers.last())

        assertNotNull(onLoadImports.resolveBoundModule("json"))
        assertNull(onConfigImports.resolveBoundModule("json"))
    }

    fun testParseUsesCurrentScriptDomainForMemberAccessCaretPosition() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                end)
                on_config(function (target)
                    json.<caret>
                end)
            target_end()
            """.trimIndent()
        )

        val place = elementAtCaret(file)

        val imports = project.xmakeApi.imports.viewAt(file, place)

        assertNull(imports.resolveBoundModule("json"))
    }

    fun testNestedBlockSeesOuterImportWithinSameHook() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    if true then
                        json.encode({})
                    end
                end)
            target_end()
            """.trimIndent()
        )

        val identifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "json" }

        assertNotNull(project.xmakeApi.imports.viewAt(file, identifier).resolveBoundModule("json"))
    }

    fun testNestedFunctionSeesOuterImportButOuterScopeDoesNotSeeNestedFunctionImport() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    local function inner()
                        json.encode({})
                        import("core.base.option")
                    end
                    json.encode({})
                    option.show_menu()
                end)
            target_end()
            """.trimIndent()
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .filter { it.text == "json" || it.text == "option" }
            .sortedBy { it.textOffset }

        val nestedJson = identifiers.first { it.text == "json" }
        val outerOption = identifiers.first { it.text == "option" }

        assertNotNull(project.xmakeApi.imports.viewAt(file, nestedJson).resolveBoundModule("json"))
        assertNull(project.xmakeApi.imports.viewAt(file, outerOption).resolveBoundModule("option"))
    }

    fun testResolvesLocalModuleFromCurrentScriptDirectory() {
        myFixture.addFileToProject(
            "modules/hello1.lua",
            """
            function greet()
            end

            function _hidden()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.hello1")
                    hello1.greet()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello1")

        assertEquals("modules.hello1", imported?.identifier)
        assertEquals(listOf("greet"), imported?.apis?.map { it.name })
    }

    fun testPrefersLocalModuleOverBuiltinModuleWithSamePath() {
        myFixture.addFileToProject(
            "core/base/json.lua",
            """
            function local_only()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.local_only()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("json")

        assertEquals("core.base.json", imported?.identifier)
        assertEquals(listOf("local_only"), imported?.apis?.map { it.name })
    }

    fun testResolvesLocalModuleFromRootDir() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hello3.greet()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello3")

        assertEquals("hello3", imported?.identifier)
        assertEquals(listOf("greet"), imported?.apis?.map { it.name })
    }

    fun testResolvesLocalModuleFromParentInterfaceFallback() {
        myFixture.addFileToProject(
            "modules/corepack.lua",
            """
            function json.encode()
            end

            function json.decode()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.corepack.json")
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("json")

        assertEquals("modules.corepack.json", imported?.identifier)
        assertEquals(listOf("decode", "encode"), imported?.apis?.map { it.name }?.sorted())
    }

    fun testResolvesRootDirLocalModuleFromMemberAccessCaretPosition() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hello3.<caret>
                end)
            target_end()
            """.trimIndent()
        )

        val place = elementAtCaret(file)

        val imported = project.xmakeApi.imports.viewAt(file, place).resolveBoundModule("hello3")

        assertEquals("hello3", imported?.identifier)
        assertEquals(listOf("greet"), imported?.apis?.map { it.name })
    }

    fun testExportsOnlyTopLevelPublicFunctionsFromLocalModule() {
        myFixture.addFileToProject(
            "modules/hello4.lua",
            """
            local function hidden_local()
            end

            function greet()
                function nested_hidden()
                end
            end

            function outer()
            end

            function module.helper()
            end

            function module:method()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.hello4")
                    hello4.greet()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello4")

        assertEquals(listOf("greet", "outer"), imported?.apis?.map { it.name }?.sorted())
    }

    fun testExportsTopLevelFunctionsAfterConditionalBlockFromLocalModule() {
        myFixture.addFileToProject(
            "modules/hello5.lua",
            """
            if is_mode("debug") then
                function hidden_debug()
                end
            elseif is_mode("release") then
                function hidden_release()
                end
            end

            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.hello5")
                    hello5.greet()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello5")

        assertEquals(listOf("greet"), imported?.apis?.map { it.name })
    }

    private fun configure(code: String): XMakeLuaFile {
        return configureXMakeLua(code)
    }
}
