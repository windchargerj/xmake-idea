package io.xmake.lang.scope.query

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.scope.issue.ScopeIssue
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.model.XMakeRoot
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

    fun testEofInConfigurationDomainKeepsConfigurationState() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("demo")
                set_kind("binary")
            <caret>
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(myFixture.file, myFixture.caretOffset)
        val issues = XMakeScopeQuery.issues(myFixture.file)

        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), state.domain)
        assertTrue(issues.none { it.kind == ScopeIssue.Kind.UNCLOSED_SCOPE })
    }

    fun testEofInUnclosedNamespaceStillReportsUnclosedScope() {
        myFixture.configureByText(
            "xmake.lua",
            """
            namespace("test")
                add_defines("NS")
            <caret>
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(myFixture.file, myFixture.caretOffset)
        val issues = XMakeScopeQuery.issues(myFixture.file)

        assertTrue(state.root is XMakeRoot.Namespace)
        assertTrue(issues.any { it.kind == ScopeIssue.Kind.UNCLOSED_SCOPE })
    }

    fun testTableOnlyStructuralEntryDoesNotOpenConfigurationDomain() {
        val identifier = configureAndFindIdentifier(
            """
            target {
                name = "demo",
                kind = "binary"
            }
            set_ki<caret>nd("binary")
            """.trimIndent()
        )
        val target = findFunctionCallIdentifier("target")

        assertTrue(XMakeScopeQuery.issuesAt(target).any { it.kind == ScopeIssue.Kind.INVALID_SCOPE_ENTRY })
        assertEquals(XMakeDomain.Description, XMakeScopeQuery.domain(identifier))
    }

    fun testMissingTargetNameReportsEntryIssueAndRecoversMatchingEnd() {
        val identifier = configureAndFindIdentifier(
            """
            tar<caret>get()
                set_kind("binary")
            target_end()
            """.trimIndent()
        )
        val targetEnd = findFunctionCallIdentifier("target_end")

        assertTrue(XMakeScopeQuery.issuesAt(identifier).any { it.kind == ScopeIssue.Kind.INVALID_SCOPE_ENTRY })
        assertTrue(XMakeScopeQuery.issuesAt(targetEnd).none { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END })
        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), XMakeScopeQuery.domain(targetEnd))
    }

    fun testEmptyTargetNameUsesRecoveryRegion() {
        val identifier = configureAndFindIdentifier(
            """
            tar<caret>get("")
                set_kind("binary")
            target_end()
            """.trimIndent()
        )
        val targetEnd = findFunctionCallIdentifier("target_end")
        val region = requireNotNull(XMakeScopeQuery.enclosingConfigurationRegion(targetEnd))

        assertTrue(XMakeScopeQuery.issuesAt(identifier).any { it.kind == ScopeIssue.Kind.INVALID_SCOPE_ENTRY })
        assertEquals(XMakeRegion.Source.RECOVERY, region.source)
        assertTrue(XMakeScopeQuery.issuesAt(targetEnd).none { it.kind == ScopeIssue.Kind.UNMATCHED_SCOPE_END })
    }

    fun testNamespaceEndRestoresConfigurationDomainFromBeforeNamespace() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                namespace("inner")
                    add_defines("NS")
                namespace_end()
                add_fi<caret>les("src/*.c")
            target_end()
            """.trimIndent()
        )

        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), XMakeScopeQuery.domain(identifier))
    }

    fun testFunctionNamespaceRestoresGlobalScopeAfterStatement() {
        val identifier = configureAndFindIdentifier(
            """
            namespace("test", function ()
                target("inside")
            end)
            tar<caret>get("outside")
            """.trimIndent()
        )

        val state = XMakeScopeQuery.stateAt(identifier)
        val issues = XMakeScopeQuery.issues(myFixture.file)

        assertEquals(XMakeDomain.Configuration(XMakeConfigurationDomainType.TARGET), state.domain)
        assertEquals(XMakeRoot.Global, state.root)
        assertTrue(issues.none { it.kind == ScopeIssue.Kind.UNCLOSED_SCOPE })
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
                    option.showm<caret>enu()
                end)
            target_end()
            """.trimIndent()
        )

        val searchRoot = XMakeScopeQuery.scriptSearchRoot(identifier)

        assertTrue(searchRoot is LuaBlock)
        assertTrue(searchRoot.text.contains("option.showmenu"))
        assertFalse(searchRoot.text.contains("import(\"core.base.json\")"))
    }

    fun testScriptSearchRootReturnsInnermostFunctionBlock() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                on_load(function (target)
                    local outer_only = true
                    local function helper()
                        local inner_only = true
                        im<caret>port("core.base.json")
                    end
                end)
            target_end()
            """.trimIndent()
        )

        val searchRoot = XMakeScopeQuery.scriptSearchRoot(identifier)

        assertTrue(searchRoot is LuaBlock)
        assertTrue(searchRoot.text.contains("inner_only"))
        assertFalse(searchRoot.text.contains("outer_only"))
    }

    fun testScriptSearchRootKeepsStructuralFunctionBodyInsideHookAsScript() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                on_load(function (target)
                    local outer_only = true
                    target("inner", function ()
                        local inner_only = true
                        set_ki<caret>nd("binary")
                    end)
                end)
            target_end()
            """.trimIndent()
        )

        val searchRoot = XMakeScopeQuery.scriptSearchRoot(identifier)

        assertTrue(searchRoot is LuaBlock)
        assertTrue(searchRoot.text.contains("inner_only"))
        assertFalse(searchRoot.text.contains("outer_only"))
    }

    fun testScriptRegionAtReturnsInnermostScriptRegion() {
        val identifier = configureAndFindIdentifier(
            """
            target("demo")
                on_load(function (target)
                    local outer_only = true
                    local function helper()
                        local inner_only = true
                        im<caret>port("core.base.json")
                    end
                end)
            target_end()
            """.trimIndent()
        )

        val scriptRegion = requireNotNull(XMakeScopeQuery.model(myFixture.file).scriptRegionAt(identifier.textOffset))
        val scriptText = myFixture.file.text.substring(scriptRegion.startOffset, scriptRegion.endOffset)

        assertTrue(scriptText.contains("inner_only"))
        assertFalse(scriptText.contains("outer_only"))
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
        return requireNotNull(
            PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false) ?: leaf as? XMakeLuaIdentifier
        )
    }

    private fun findFunctionCallIdentifier(name: String): XMakeLuaIdentifier =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .first { it.text == name }

    private fun configureAndFindElement(code: String): PsiElement {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        return myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
    }

    private fun configure(code: String) = myFixture.configureByText("xmake.lua", code)
}
