package io.xmake.lang.declarations

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.import.ImportBindingOrigin
import io.xmake.lang.declarations.import.ImportedModuleView
import io.xmake.lang.declarations.import.findIndexedModuleExports
import io.xmake.lang.declarations.import.toImportedModuleView
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

/**
 * Plugin-local import lookup coverage across script bodies.
 *
 * This suite protects how the IDE associates imported modules with the current
 * hook/script lookup region. It is intentionally separate from [ApiDomainAvailabilityTest]
 * because xmake docs describe import usage but do not define this precise
 * editor-facing lookup contract.
 */
class ApiImportLookupTest : XMakeTestCase() {

    fun testImportedModuleLookupIsScopedToCurrentScriptDomain() {
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

        val loadJson = identifiers.first()
        val configJson = identifiers.last()

        val loadImports = project.xmakeApi.imports.viewAt(file, loadJson)
        val configImports = project.xmakeApi.imports.viewAt(file, configJson)

        assertNotNull(loadImports.resolveBoundModule("json"))
        assertNull(configImports.resolveBoundModule("json"))
    }

    fun testCentralImportLookupReturnsImportedModuleViews() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val identifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "json" }

        val api = project.xmakeApi
        val importView = api.imports.viewAt(file, identifier)
        val fileModules: List<ImportedModuleView> = importView.importedModules
        val importedModule: ImportedModuleView? = importView.resolveBoundModule("json")
        val indexedModule: ImportedModuleView? = api.lookup.findIndexedModuleExports("core.base.json")?.toImportedModuleView(
            primaryReceiverName = "json",
            primaryBoundName = "json"
        )

        assertEquals("core.base.json", importView.resolveBoundModule("json")?.identifier)
        assertEquals(listOf("core.base.json"), fileModules.map { it.identifier })
        assertEquals("core.base.json", importedModule?.identifier)
        assertEquals("core.base.json", indexedModule?.identifier)
    }

    fun testImportLookupReturnsAddImportsBindingTargetOrigin() {
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

        val identifier = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .first { it.text == "json" }

        val binding = project.xmakeApi.imports.findReceiverBindingTargets(file, "json", identifier)?.primary

        assertEquals(ImportBindingOrigin.ADD_IMPORTS, binding?.origin)
        assertEquals("core.base.json", binding?.module?.identifier)
    }

    fun testImportLookupReturnsReceiverBindingConflictsFromXMakeApiWrapper() {
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

        val targets = project.xmakeApi.imports.findReceiverBindingTargets(file, "json")

        assertEquals("core.base.option", targets?.primary?.module?.identifier)
        assertEquals(listOf("core.base.json"), targets?.conflicts?.map { it.module.identifier })
    }

    fun testLocalImportedModuleViewCarriesDeclarations() {
        myFixture.addFileToProject(
            "modules/hello_decl.lua",
            """
            function greet()
            end
            """.trimIndent()
        )
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("modules.hello_decl")
                    hello_decl.greet()
                end)
            target_end()
            """.trimIndent()
        )

        val imported = project.xmakeApi.imports.findReceiverModule(file, "hello_decl")

        assertNotNull(imported)
        assertEquals(listOf("greet"), imported?.declarations?.map { it.name })
        assertNotNull(imported?.declarations?.singleOrNull()?.declarationOffset)
        assertEquals("greet", imported?.declarations?.singleOrNull()?.declarationText)
    }

    fun testCentralImportLookupReturnsInheritedApis() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    encode({})
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals("encode", project.xmakeApi.imports.viewAt(file).resolveInheritedApiExposures("encode")?.primary?.api?.name)
    }

    fun testImportLookupCacheInvalidatesAfterFileEdit() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val before = project.xmakeApi.imports.viewAt(file)
        assertNotNull(before.resolveBoundModule("json"))

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(
                """
                target("demo")
                    on_load(function (target)
                        import("core.base.option")
                        option.show_menu()
                    end)
                target_end()
                """.trimIndent()
            )
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val after = project.xmakeApi.imports.viewAt(myFixture.file as XMakeLuaFile)
        assertNull(after.resolveBoundModule("json"))
        assertNotNull(after.resolveBoundModule("option"))
    }

    private fun configure(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }
}
