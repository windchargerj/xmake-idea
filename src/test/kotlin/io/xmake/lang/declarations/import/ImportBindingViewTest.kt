package io.xmake.lang.declarations.import

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import org.junit.Assert.assertNotEquals

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
                    json.showmenu()
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

    fun testAddImportsAppliesToHookEvenWhenDeclaredAfterHook() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    json.encode({})
                end)
                add_imports("core.base.json")
            target_end()
            """.trimIndent()
        )

        val jsonIdentifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "json" }
        val imports = project.xmakeApi.imports.viewAt(file, jsonIdentifier)

        assertEquals("core.base.json", imports.resolveReceiverModule("json")?.identifier)
        assertEquals(ImportBindingOrigin.ADD_IMPORTS, imports.resolveReceiverBindingTargets("json")?.primary?.origin)
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
                    option.showmenu()
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

    fun testResolvesLeadingDotRelativeLocalModuleFromParentDirectory() {
        myFixture.addFileToProject(
            "src/sibling.lua",
            """
            function ping()
            end
            """.trimIndent()
        )
        val script = myFixture.addFileToProject(
            "src/app/xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import(".sibling")
                    sibling.ping()
                end)
            target_end()
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(script.virtualFile)
        val file = myFixture.file as XMakeLuaFile

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("sibling")

        assertEquals(".sibling", imported?.identifier)
        assertEquals(listOf("ping"), imported?.apis?.map { it.name })
    }

    fun testResolvesDoubleLeadingDotRelativeLocalModule() {
        myFixture.addFileToProject(
            "src/parent/mod.lua",
            """
            function ping()
            end
            """.trimIndent()
        )
        val script = myFixture.addFileToProject(
            "src/app/nested/xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("..parent.mod")
                    mod.ping()
                end)
            target_end()
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(script.virtualFile)
        val file = myFixture.file as XMakeLuaFile

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("mod")

        assertEquals("..parent.mod", imported?.identifier)
        assertEquals(listOf("ping"), imported?.apis?.map { it.name })
    }

    fun testResolvesDirectoryModuleAsUnknownSurface() {
        myFixture.addFileToProject(
            "modules/bundle/entry.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.bundle")
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("bundle")

        assertEquals("modules.bundle", imported?.identifier)
        assertEquals(ImportedObjectKind.DIRECTORY, imported?.kind)
        assertTrue(imported?.hasUnknownMembers == true)
        assertTrue(imported?.apis.orEmpty().isEmpty())
    }

    fun testResolvesNativeModuleDirectoriesAsUnknownSurface() {
        myFixture.addFileToProject(
            "native/binmod/xmake.lua",
            """
            target("binmod")
                add_rules("module.binary")
            target_end()
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "native/sharedmod/xmake.lua",
            """
            target("sharedmod")
                add_rules("module.shared")
            target_end()
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("native.binmod")
                    import("native.sharedmod")
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals("native.binmod", imports.resolveBoundModule("binmod")?.identifier)
        assertEquals("native.sharedmod", imports.resolveBoundModule("sharedmod")?.identifier)
        assertEquals(ImportedObjectKind.NATIVE_BINARY, imports.resolveBoundModule("binmod")?.kind)
        assertEquals(ImportedObjectKind.NATIVE_SHARED, imports.resolveBoundModule("sharedmod")?.kind)
        assertTrue(imports.resolveBoundModule("binmod")?.hasUnknownMembers == true)
        assertTrue(imports.resolveBoundModule("sharedmod")?.hasUnknownMembers == true)
        assertTrue(imports.resolveBoundModule("binmod")?.apis.orEmpty().isEmpty())
        assertTrue(imports.resolveBoundModule("sharedmod")?.apis.orEmpty().isEmpty())
    }

    fun testDoesNotTreatSingleQuotedNativeRuleAsNativeModule() {
        myFixture.addFileToProject(
            "native/quoted/xmake.lua",
            """
            target("quoted")
                add_rules('module.binary')
            target_end()
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("native.quoted", {try = true})
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(project.xmakeApi.imports.viewAt(file).resolveBoundModule("quoted"))
    }

    fun testDoesNotResolveParentInterfaceMemberFallbackAsModuleExports() {
        myFixture.addFileToProject(
            "modules/corepack.lua",
            """
            json = {}

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

        // Official source: import.lua only accepts parent fallback when module2[interface_name]
        // exists; sandbox.lua sandbox:module() does not export `function json.encode()` as `json`.
        assertNull(imported)
    }

    fun testParentFallbackOnlyProvesTopLevelPublicParentExport() {
        myFixture.addFileToProject(
            "modules/corepack.lua",
            """
            function json()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.corepack.json")
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("json")

        assertEquals("modules.corepack.json", imported?.identifier)
        assertEquals(ImportedObjectKind.CALLABLE, imported?.kind)
        assertTrue(imported?.apis.orEmpty().isEmpty())
    }

    fun testRootDirOverridesCurrentScriptDirectoryForLocalSearch() {
        myFixture.addFileToProject(
            "src/hello.lua",
            """
            function wrong()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/modules/hello.lua",
            """
            function right()
            end
            """.trimIndent()
        )
        val script = myFixture.addFileToProject(
            "src/xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "modules"})
                end)
            target_end()
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(script.virtualFile)
        val file = myFixture.file as XMakeLuaFile

        val imported = project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello")

        assertEquals(listOf("right"), imported?.apis?.map { it.name })
    }

    fun testNoLocalSkipsCurrentScriptDirectoryForImportResolution() {
        myFixture.addFileToProject(
            "src/json.lua",
            """
            function local_only()
            end
            """.trimIndent()
        )
        val script = myFixture.addFileToProject(
            "src/xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("json", {nolocal = true})
                end)
            target_end()
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(script.virtualFile)
        val file = myFixture.file as XMakeLuaFile

        assertNull(project.xmakeApi.imports.viewAt(file).resolveBoundModule("json"))
    }

    fun testImportedModuleFileEditInvalidatesExports() {
        val moduleFile = myFixture.addFileToProject(
            "modules/live.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.live")
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(listOf("greet"), project.xmakeApi.imports.viewAt(file).resolveBoundModule("live")?.apis?.map { it.name })

        val document = requireNotNull(PsiDocumentManager.getInstance(project).getDocument(moduleFile))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(
                """
                function wave()
                end
                """.trimIndent()
            )
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        assertEquals(listOf("wave"), project.xmakeApi.imports.viewAt(file).resolveBoundModule("live")?.apis?.map { it.name })
    }

    fun testSameModulePathWithDifferentRootDirsKeepsSeparateIdentities() {
        myFixture.addFileToProject(
            "mods/a/hello.lua",
            """
            function from_a()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "mods/b/hello.lua",
            """
            function from_b()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "mods/a", alias = "ha"})
                    import("hello", {rootdir = "mods/b", alias = "hb"})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertEquals(listOf("from_a"), imports.resolveBoundModule("ha")?.apis?.map { it.name })
        assertEquals(listOf("from_b"), imports.resolveBoundModule("hb")?.apis?.map { it.name })
        assertNotEquals(imports.resolveBoundModule("ha")?.identity, imports.resolveBoundModule("hb")?.identity)
    }

    fun testAliasRootDirDoesNotExposeOriginalModulePathAsBinding() {
        myFixture.addFileToProject(
            "mods/a/hello.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "mods/a", alias = "h"})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)
        assertEquals(listOf("greet"), imports.resolveBoundModule("h")?.apis?.map { it.name })
        assertNull(imports.resolveBoundModule("hello"))
        assertNull(imports.resolveReceiverModule("hello"))
    }

    fun testLocalImportCaptureSkipsLuaAttributesWhenMappingReturnSlots() {
        myFixture.addFileToProject(
            "modules/a.lua",
            """
            function from_a()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "modules/b.lua",
            """
            function from_b()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    local a <const>, b = import("modules.a"), import("modules.b")
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)
        assertEquals(listOf("from_a"), imports.resolveBoundModule("a")?.apis?.map { it.name })
        assertEquals(listOf("from_b"), imports.resolveBoundModule("b")?.apis?.map { it.name })
        assertNull(imports.resolveBoundModule("const"))
    }

    fun testSameAliasWithDifferentRootDirsIsConflictNotShadow() {
        myFixture.addFileToProject(
            "mods/a/hello.lua",
            """
            function from_a()
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "mods/b/hello.lua",
            """
            function from_b()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "mods/a", alias = "h"})
                    import("hello", {rootdir = "mods/b", alias = "h"})
                end)
            target_end()
            """.trimIndent()
        )

        val targets = requireNotNull(project.xmakeApi.imports.viewAt(file).resolveBindingTargets("h"))

        assertEquals(listOf("from_b"), targets.primary.module.apis.map { it.name })
        assertTrue(targets.shadowed.isEmpty())
        assertEquals(listOf("from_a"), targets.conflicts.single().module.apis.map { it.name })
    }

    fun testEquivalentRootDirSpellingsShareIdentity() {
        myFixture.addFileToProject(
            "mods/a/hello.lua",
            """
            function from_a()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "mods/a", alias = "h"})
                    import("hello", {rootdir = "./mods/a", alias = "h"})
                end)
            target_end()
            """.trimIndent()
        )

        val targets = requireNotNull(project.xmakeApi.imports.viewAt(file).resolveBindingTargets("h"))

        assertEquals(listOf("from_a"), targets.primary.module.apis.map { it.name })
        assertEquals(1, targets.shadowed.size)
        assertTrue(targets.conflicts.isEmpty())
        assertEquals(targets.primary.module.identity, targets.shadowed.single().module.identity)
    }

    fun testRelativeRootDirParentTraversalIsRejected() {
        myFixture.addFileToProject(
            "outside/hello.lua",
            """
            function outside()
            end
            """.trimIndent()
        )
        val script = myFixture.addFileToProject(
            "src/xmake.lua",
            """
            target("demo")
                on_load(function (target)
                    import("hello", {rootdir = "../outside"})
                end)
            target_end()
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(script.virtualFile)
        val file = myFixture.file as XMakeLuaFile

        assertNull(project.xmakeApi.imports.viewAt(file).resolveBoundModule("hello"))
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
