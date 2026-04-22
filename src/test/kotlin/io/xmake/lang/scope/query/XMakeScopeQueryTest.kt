package io.xmake.lang.scope.query

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.scope.issue.ScopeIssue
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaBlock
import org.junit.Assert.assertNotEquals

class XMakeScopeQueryTest : XMakeTestCase() {

    fun testIssuesReportMismatchedScopeEndWithoutDroppingCurrentScope() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                optio<caret>n_end()
                set_kind("binary")
            target_end()
            """.trimIndent()
        )

        val issues = XMakeScopeQuery.issuesAt(identifier)
        assertTrue(issues.any { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END })
        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), XMakeScopeQuery.domain(identifier))
    }

    fun testIssuesReportUnmatchedNamespaceEndWithoutLeavingGlobalScope() {
        val identifier = configureAndFindIdentifier(
            """
            name<caret>space_end()
            add_defines("GLOBAL")
            """.trimIndent()
        )

        val issues = XMakeScopeQuery.issuesAt(identifier)
        assertTrue(issues.any { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END })
        assertEquals(XMakeDomain.Description, XMakeScopeQuery.domain(identifier))
    }

    fun testNamespaceEndClosesNamespaceAfterImplicitTargetEnd() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("test")
                target("hello")
                    add_files("src/*.c")
            namespace<caret>_end()
            """.trimIndent()
        )

        val issues = XMakeScopeQuery.issuesAt(identifier)
        assertTrue(issues.none { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END })
        assertEquals(XMakeDomain.Description, XMakeScopeQuery.domain(identifier))
    }

    fun testEofDoesNotReportUnclosedConfigurationScope() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                set_kind("binary")
                add_fi<caret>les("src/*.c")
            """.trimIndent()
        )

        val issues = XMakeScopeQuery.issues(myFixture.file)
        assertTrue(issues.none { it.kind == ScopeIssue.Kind.UNCLOSED_SCOPE })
        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), XMakeScopeQuery.domain(identifier))
    }

    fun testEofStillReportsUnclosedNamespaceScope() {
        configureAndFindIdentifier(
            """
            namespace("test")
                add_defi<caret>nes("NS")
            """.trimIndent()
        )

        val issues = XMakeScopeQuery.issues(myFixture.file)
        assertTrue(issues.any { it.kind == ScopeIssue.Kind.UNCLOSED_SCOPE })
    }

    fun testEnclosingConfigurationRegionTracksCurrentScopeOnly() {
        val file = configure(
            """
            target("demo")
                on_load(function (target)
                    add_de<caret>fines("DEMO")
                end)
            target_end()
            target("other")
                on_load(function (target)
                    add_defines("OTHER")
                end)
            target_end()
            """.trimIndent()
        )

        val identifiers = PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .filter { it.text == "add_defines" }
            .sortedBy { it.textOffset }

        val demoRegion = requireNotNull(XMakeScopeQuery.enclosingConfigurationRegion(identifiers.first()))
        val otherRegion = requireNotNull(XMakeScopeQuery.enclosingConfigurationRegion(identifiers.last()))

        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), demoRegion.domain)
        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), otherRegion.domain)
        assertNotEquals(demoRegion.range, otherRegion.range)
    }

    fun testScriptSearchRootReturnsCurrentHookBlock() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                on_load(function (target)
                    import("core.base.json")
                end)
                on_config(function (target)
                    option.show_m<caret>enu()
                end)
            target_end()
            """.trimIndent()
        )

        val searchRoot = XMakeScopeQuery.scriptSearchRoot(identifier)

        assertTrue(searchRoot is LuaBlock)
        assertTrue(searchRoot.text.contains("option.show_menu"))
        assertFalse(searchRoot.text.contains("import(\"core.base.json\")"))
    }

    fun testScriptSearchRootFallsBackToFileOutsideScriptScope() {
        val identifier = configureAndFindIdentifier(
            """
            tar<caret>get("demo")
                on_load(function (target)
                end)
            target_end()
            """.trimIndent()
        )

        val searchRoot = XMakeScopeQuery.scriptSearchRoot(identifier)

        assertEquals(myFixture.file, searchRoot)
    }

    private fun configureAndFindIdentifier(code: String): XMakeLuaIdentifier {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val leaf: PsiElement = myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
        return requireNotNull(PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false) ?: leaf as? XMakeLuaIdentifier)
    }

    private fun configureAndFindElement(code: String): PsiElement {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        return myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
    }

    private fun configure(code: String) = myFixture.configureByText("xmake.lua", code)
}
