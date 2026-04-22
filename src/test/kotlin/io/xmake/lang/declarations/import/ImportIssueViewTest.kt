package io.xmake.lang.declarations.import

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

/**
 * Plugin-local issue aggregation coverage for import lookup view building.
 *
 * These assertions protect the resilient path: malformed declarations should
 * surface issues without breaking valid imported-module lookup in the same domain.
 */
class ImportIssueViewTest : XMakeTestCase() {

    fun testMalformedImportRecordsIssueButKeepsValidImportsResolvable() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("")
                    import("core.base.json")
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertTrue(imports.hasIssues)
        assertTrue(imports.issues.any { it.kind == ImportIssue.Kind.DECLARATION_PARSE_FAILED })
        assertEquals("core.base.json", imports.resolveBoundModule("json")?.identifier)
    }

    fun testMalformedAddImportsRecordsIssueWithoutCreatingBinding() {
        val file = configure(
            """
            target("demo")
                add_imports(true)
                on_load(function (target)
                    json.encode({})
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertTrue(imports.hasIssues)
        assertTrue(imports.issues.any { it.kind == ImportIssue.Kind.ADD_IMPORT_PARSE_FAILED })
        assertNull(imports.resolveBoundModule("json"))
    }

    fun testMalformedAddImportsArrayItemRecordsIssueButKeepsValidItemsResolvable() {
        val file = configure(
            """
            target("demo")
                add_imports({"core.base.json", true, "core.base.option"})
                on_load(function (target)
                    json.encode({})
                    option.showmenu()
                end)
            target_end()
            """.trimIndent()
        )

        val imports = project.xmakeApi.imports.viewAt(file)

        assertTrue(imports.hasIssues)
        assertTrue(imports.issues.any { it.kind == ImportIssue.Kind.ADD_IMPORT_PARSE_FAILED })
        assertEquals("core.base.json", imports.resolveBoundModule("json")?.identifier)
        assertEquals("core.base.option", imports.resolveBoundModule("option")?.identifier)
    }

    private fun configure(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }
}
