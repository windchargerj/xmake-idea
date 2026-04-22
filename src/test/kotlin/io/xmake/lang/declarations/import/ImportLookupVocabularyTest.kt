package io.xmake.lang.declarations.import

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.syntax.psi.XMakeLuaFile

class ImportLookupVocabularyTest : XMakeTestCase() {

    fun testImportLookupExposesLookupViewAtLocation() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    enc<caret>ode({})
                end)
            target_end()
            """.trimIndent()
        )

        val identifier = identifierAtCaret()
        val view: ImportLookupView = project.xmakeApi.imports.viewAt(file, identifier)

        assertEquals("encode", view.resolveInheritedApiExposures("encode")?.primary?.api?.name)
    }

    private fun configure(code: String): XMakeLuaFile = configureXMakeLua(code)
}
