package io.xmake.lang.editor.folding

import com.intellij.testFramework.EditorTestUtil
import io.xmake.lang.XMakeTestCase

class XMakeLuaFoldingBuilderTest : XMakeTestCase() {

    fun testBuildsConfigurationFoldRegionForTargetBlock() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                set_kind("binary")
                add_files("src/*.c")
            target_end()
            """.trimIndent()
        )

        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        myFixture.doHighlighting()

        val foldRegions = myFixture.editor.foldingModel.allFoldRegions.toList()
        assertTrue(foldRegions.isNotEmpty())
        assertTrue(foldRegions.any { it.placeholderText == "target(\"demo\")" })
    }

    fun testBuildsFoldRegionForNamespaceBlock() {
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

        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        myFixture.doHighlighting()

        val foldRegions = myFixture.editor.foldingModel.allFoldRegions.toList()
        assertTrue(foldRegions.any { it.placeholderText == "namespace(\"demo\")" })
        assertTrue(foldRegions.any { it.placeholderText == "target(\"app\")" })
    }
}
