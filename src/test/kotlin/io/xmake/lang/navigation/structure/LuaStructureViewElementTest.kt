package io.xmake.lang.navigation.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

class LuaStructureViewElementTest : XMakeTestCase() {

    fun testBuildsTopLevelDomainChildrenFromXMakeRegions() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                add_files("src/main.cpp")
            target_end()
            option("feat")
                set_default(false)
            option_end()
            """.trimIndent()
        )

        val root = LuaStructureViewElement(myFixture.file)
        val children = root.children
            .filterIsInstance<StructureViewTreeElement>()
            .mapNotNull { it.presentation.presentableText }

        assertEquals(listOf("Target", "Option"), children)
    }

    fun testDomainPresentationReadsNameFromOpeningCall() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target('demo')
                add_files("src/main.cpp")
            target_end()
            """.trimIndent()
        )

        val file = myFixture.file
        val call = requireNotNull(PsiTreeUtil.findChildOfType(file, LuaFunctionCall::class.java))
        val presentation = XMakeScopeItemPresentation(call)

        assertEquals("Target", presentation.presentableText)
        assertEquals("demo", presentation.locationString)
    }
}

