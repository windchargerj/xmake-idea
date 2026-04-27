package io.xmake.lang.scope.query

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeScopeQueryNamespaceRootTest : XMakeTestCase() {

    fun testStateMarksNamespaceRootFromPsi() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("ns")
                add_de<caret>fines("NS_ROOT")
            namespace_end()
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        assertEquals(XMakeDomain.Description, state.domain)
        assertEquals(XMakeRegion.Source.PSI, state.source)
        assertTrue(state.root is XMakeRoot.Namespace)
    }

    fun testRegionsExposeGlobalAndNamespaceScopes() {
        myFixture.configureByText(
            "xmake.lua",
            """
            namespace("ns")
                target("demo")
                    add_de<caret>fines("NS_ROOT")
                target_end()
            namespace_end()
            """.trimIndent()
        )

        val regions = XMakeScopeQuery.regions(myFixture.file)
        assertTrue(regions.any { it.domain is XMakeDomain.Description })
        assertTrue(regions.any { it.domain is XMakeDomain.Description && it.root is XMakeRoot.Namespace })
        assertTrue(regions.any { it.domain is XMakeDomain.Configuration && it.root is XMakeRoot.Namespace })
    }

    fun testNestedNamespaceStateRemainsInNamespaceRoot() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("outer")
                namespace("inner")
                    add_de<caret>fines("NS_ROOT")
                namespace_end()
            namespace_end()
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        assertEquals(XMakeDomain.Description, state.domain)
        assertTrue(state.root is XMakeRoot.Namespace)
    }

    fun testNestedNamespaceStatePreservesParentChain() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("outer")
                namespace("inner")
                    add_de<caret>fines("NS_ROOT")
                namespace_end()
            namespace_end()
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        val innerRoot = state.root as? XMakeRoot.Namespace ?: error("Expected namespace root")
        val outerRoot = innerRoot.parent as? XMakeRoot.Namespace ?: error("Expected nested namespace parent")
        assertEquals(XMakeRoot.Global, outerRoot.parent)
        assertEquals("inner", innerRoot.name)
        assertEquals("outer", outerRoot.name)
        assertEquals(listOf("outer", "inner"), innerRoot.path)
    }

    fun testInnerNamespaceEndKeepsOuterNamespaceRootActive() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("outer")
                namespace("inner")
                namespace_end()
                add_de<caret>fines("OUTER")
            namespace_end()
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        assertEquals(XMakeDomain.Description, state.domain)
        val root = state.root as? XMakeRoot.Namespace ?: error("Expected namespace root")
        assertEquals(XMakeRoot.Global, root.parent)
        assertEquals("outer", root.name)
    }

    fun testSiblingNamespacesHaveDistinctIdentity() {
        myFixture.configureByText(
            "xmake.lua",
            """
            namespace("left")
                add_defines("LEFT")
            namespace_end()

            namespace("right")
                add_de<caret>fines("RIGHT")
            namespace_end()
            """.trimIndent()
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .filter { it.text == "add_defines" }
            .sortedBy { it.textOffset }

        val leftRoot = XMakeScopeQuery.stateAt(identifiers.first()).root as? XMakeRoot.Namespace
            ?: error("Expected left namespace")
        val rightRoot = XMakeScopeQuery.stateAt(identifiers.last()).root as? XMakeRoot.Namespace
            ?: error("Expected right namespace")

        assertFalse(leftRoot == rightRoot)
        assertEquals(listOf("left"), leftRoot.path)
        assertEquals(listOf("right"), rightRoot.path)
    }

    fun testDynamicNamespaceNameUsesAnonymousIdentity() {
        val identifier = configureAndFindIdentifier(
            """
            local ns = "dynamic"
            namespace(ns)
                add_de<caret>fines("NS_ROOT")
            namespace_end()
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        val root = state.root as? XMakeRoot.Namespace ?: error("Expected namespace root")

        assertNull(root.name)
        assertTrue(root.path.isEmpty())
        assertFalse(root.identityPath.contains("ns"))
        assertTrue(root.identity.isNotBlank())
    }

    private fun configureAndFindIdentifier(code: String): XMakeLuaIdentifier {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val leaf: PsiElement = myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
        return requireNotNull(
            PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false) ?: leaf as? XMakeLuaIdentifier
        )
    }
}
