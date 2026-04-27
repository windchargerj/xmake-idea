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

    fun testPreservesNamespaceAsStructureNode() {
        myFixture.configureByText(
            "xmake.lua",
            """
            namespace("demo")
                target("app")
                    add_files("src/main.cpp")
                target_end()
            namespace_end()
            option("root")
                set_default(false)
            option_end()
            """.trimIndent()
        )

        val root = LuaStructureViewElement(myFixture.file)
        val children = root.children.filterIsInstance<StructureViewTreeElement>()
        val namespace = children.single { it.presentation.presentableText == "Namespace" }
        val namespaceChildren = namespace.children
            .filterIsInstance<StructureViewTreeElement>()
            .map { it.presentation.presentableText to it.presentation.locationString }

        assertEquals(listOf("Namespace", "Option"), children.map { it.presentation.presentableText })
        assertEquals("demo", namespace.presentation.locationString)
        assertEquals(listOf("Target" to "app"), namespaceChildren)
    }

    fun testStructureModelTreatsNamespaceWithChildrenAsExpandable() {
        myFixture.configureByText(
            "xmake.lua",
            """
            namespace("demo")
                target("app")
                    add_files("src/main.cpp")
                target_end()
            namespace_end()
            """.trimIndent()
        )

        val model = LuaStructureViewModel(myFixture.file as io.xmake.lang.syntax.psi.XMakeLuaFile)
        val namespace = model.root.children
            .filterIsInstance<StructureViewTreeElement>()
            .single { it.presentation.presentableText == "Namespace" }

        assertFalse(model.isAlwaysLeaf(namespace))
        assertTrue(model.isAlwaysShowsPlus(namespace))
    }

    fun testAlphaSortKeyUsesDisplayedStructureName() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("zeta")
                add_files("src/main.cpp")
            target_end()
            """.trimIndent()
        )

        val child = LuaStructureViewElement(myFixture.file).children
            .filterIsInstance<LuaStructureViewElement>()
            .single()

        assertEquals("Target zeta", child.alphaSortKey)
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

