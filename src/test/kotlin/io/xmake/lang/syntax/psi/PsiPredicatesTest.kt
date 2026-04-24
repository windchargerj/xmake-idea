package io.xmake.lang.syntax.psi

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase

class PsiPredicatesTest : XMakeTestCase() {

    fun testRecognizesLocalFunctionDeclarationName() {
        val identifier = configureAndFindIdentifier(
            """
            local function gre<caret>et(name)
                return name
            end
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isFunctionDeclarationName(identifier))
        assertTrue(PsiPredicates.isBindableFunctionDeclarationName(identifier))
    }

    fun testRecognizesLocalVariableBeforeAssignmentBoundary() {
        val identifier = configureAndFindIdentifier(
            """
            local al<caret>ias, other = path, os
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isLocalVariable(identifier))
    }

    fun testRecognizesAssignmentTarget() {
        val identifier = configureAndFindIdentifier(
            """
            ali<caret>as = path
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isAssignmentTarget(identifier))
    }

    fun testRecognizesGotoLabelReference() {
        val identifier = configureAndFindIdentifier(
            """
            goto fi<caret>nish
            ::finish::
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isGotoLabelReference(identifier))
    }

    fun testRecognizesTableFieldAccess() {
        val identifier = configureAndFindIdentifier(
            """
            print(core.ba<caret>se)
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isTableFieldAccess(identifier))
    }

    fun testRecognizesUnresolvedVariableCandidate() {
        val identifier = configureAndFindIdentifier(
            """
            print(unres<caret>olved)
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isUnresolvedVariableCandidate(identifier))
    }

    fun testRecognizesDeclaration() {
        val localVariable = configureAndFindIdentifier(
            """
            local ali<caret>as = path
            """.trimIndent()
        )
        assertTrue(PsiPredicates.isDeclaration(localVariable))

        val parameter = configureAndFindIdentifier(
            """
            function run(pa<caret>ram)
            end
            """.trimIndent()
        )
        assertTrue(PsiPredicates.isDeclaration(parameter))

        val functionName = configureAndFindIdentifier(
            """
            function bu<caret>ild()
            end
            """.trimIndent()
        )
        assertTrue(PsiPredicates.isDeclaration(functionName))
    }

    fun testRecognizesTableKeyWithoutTreatingValueAsKey() {
        val key = configureAndFindIdentifier(
            """
            import("core.base.json", { ali<caret>as = "j" })
            """.trimIndent()
        )
        assertTrue(PsiPredicates.isTableKey(key))

        val value = configureAndFindIdentifier(
            """
            import("core.base.json", { alias = <caret>path })
            """.trimIndent()
        )
        assertFalse(PsiPredicates.isTableKey(value))
    }

    fun testRecognizesForLoopVariable() {
        val identifier = configureAndFindIdentifier(
            """
            for i<caret>, value in ipairs(items) do
                print(value)
            end
            """.trimIndent()
        )

        assertTrue(PsiPredicates.isForLoopVariable(identifier))
        assertTrue(PsiPredicates.isLocalVariable(identifier))
    }

    fun testParameterDeclarationScopeStopsAtFunctionBodyBoundary() {
        myFixture.configureByText(
            "xmake.lua",
            """
            target("one")
                after_build(function (ta<caret>rget)
                    print(target:name())
                end)
            target("two")
                set_kind("binary")
            target_end()
            """.trimIndent()
        )

        val parameter = configureAndFindIdentifierAtCaret(myFixture.caretOffset)
        val scope = requireNotNull(PsiPredicates.declarationScope(parameter))
        val topLevelTargets = PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .filter { it.text == "target" && PsiPredicates.isFunctionCall(it) }
            .sortedBy { it.textOffset }

        assertTrue(scope.textRange.contains(parameter.textOffset))
        assertFalse(scope.textRange.contains(topLevelTargets.last().textOffset))
    }

    private fun configureAndFindIdentifier(code: String): XMakeLuaIdentifier {
        myFixture.configureByText("xmake.lua", code)
        return configureAndFindIdentifierAtCaret(myFixture.caretOffset)
    }

    private fun configureAndFindIdentifierAtCaret(caretOffset: Int): XMakeLuaIdentifier {
        val directLeaf = myFixture.file.findElementAt(caretOffset)
        val directIdentifier = directLeaf?.let {
            PsiTreeUtil.getParentOfType(it, XMakeLuaIdentifier::class.java, false) ?: it as? XMakeLuaIdentifier
        }
        if (directIdentifier != null) {
            return directIdentifier
        }

        val previousLeaf = myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
        return requireNotNull(
            previousLeaf?.let {
                PsiTreeUtil.getParentOfType(it, XMakeLuaIdentifier::class.java, false) ?: it as? XMakeLuaIdentifier
            }
        )
    }
}
