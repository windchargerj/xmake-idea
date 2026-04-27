package io.xmake.lang.codeInsight.reference

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.resolution.XMakeApiDeclarationService
import io.xmake.lang.resolution.reference.XMakeReferenceProvider
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

/**
 * Reference resolution tests focus on semantic ownership and resolve targets.
 *
 * They should avoid locking in PSI layout details such as exact offsets or child ordering,
 * because those details are more brittle than the user-facing resolve contract.
 *
 * Plugin-local imported binding carrier tests live in
 * [XMakeReferenceResolverTest].
 */
class XMakeReferenceResolveTest : XMakeTestCase() {

    private val provider = XMakeReferenceProvider()

    fun testLocalVariableReferenceResolvesToDeclaration() {
        val reference = configureAndFindReference("""
            local value = 1
            local copy = val<caret>ue
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("value", resolved.name)
    }

    fun testParameterReferenceResolvesToParameterDeclaration() {
        val reference = configureAndFindReference("""
            local function greet(name)
                local copy = na<caret>me
            end
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("name", resolved.name)
    }

    fun testGenericForVariableReferenceResolvesToHeaderDeclaration() {
        val reference = configureAndFindReference("""
            for _, name in ipairs({"pthread", "dl"}) do
                local copy = na<caret>me
            end
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("name", resolved.name)
    }

    fun testImplicitGlobalAssignmentReferenceResolvesToAssignmentTarget() {
        val reference = configureAndFindReference("""
            value = 1
            local copy = va<caret>lue
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("value", resolved.name)
    }

    fun testGotoLabelReferenceResolvesToDeclaration() {
        val reference = configureAndFindReference("""
            goto do<caret>ne
            ::done::
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("done", resolved.name)
    }

    fun testMissingGotoLabelDoesNotResolveToLocalVariable() {
        val reference = configureAndFindReference("""
            local done = 1
            goto do<caret>ne
        """.trimIndent())

        assertNull(reference.resolve())
    }

    fun testQualifiedFunctionDeclarationDoesNotResolvePlainCall() {
        val reference = configureAndFindReference("""
            local mod = {}
            function mod.foo()
            end
            fo<caret>o()
        """.trimIndent())

        assertNull(reference.resolve())
    }

    fun testLocalFunctionCallResolvesToDeclaration() {
        val reference = configureAndFindReference("""
            local function greet(name)
                return name
            end
            gre<caret>et("codex")
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("greet", resolved.name)
    }

    fun testLocalFunctionShadowsBuiltinApi() {
        val reference = configureAndFindReference("""
            local function add_files(pattern)
                return pattern
            end
            add_f<caret>iles("src/*.c")
        """.trimIndent())

        val resolved = requireNotNull(reference.resolve() as? XMakeLuaIdentifier)
        assertEquals("add_files", resolved.name)
    }

    fun testPlatformFindReferenceAtCanResolveLocalVariable() {
        myFixture.configureByText("xmake.lua", """
            local value = 1
            local copy = val<caret>ue
        """.trimIndent())

        val caretOffset = myFixture.caretOffset
        val reference = myFixture.file.findReferenceAt(caretOffset)
            ?: myFixture.file.findReferenceAt((caretOffset - 1).coerceAtLeast(0))

        val resolved = requireNotNull(reference?.resolve() as? XMakeLuaIdentifier)
        assertEquals("value", resolved.name)
    }

    fun testMemberIdentifierOwnsModuleReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent(),
            expectedName = "join"
        )
    }

    fun testMemberIdentifierOwnsInstanceMethodReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    target:a<caret>dd("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent(),
            expectedName = "add"
        )
    }

    fun testBuiltinModuleFunctionReferenceResolvesToSyntheticApiDeclaration() {
        val reference = configureAndFindReference(
            """
            local joined = path.jo<caret>in("src", "main.c")
            """.trimIndent(),
            expectedName = "join"
        )

        val resolved = reference.resolve() as? XMakeLuaIdentifier
        assertNotNull(resolved)
        assertEquals("join", resolved?.name)
        assertEquals(XMakeApiDeclarationService.SYNTHETIC_FILE_NAME, resolved?.containingFile?.name)
        assertTrue(resolved?.containingFile?.text?.contains("function path.join() end") == true)
    }

    fun testVerifiedHookReceiverInstanceMethodResolvesToSyntheticApiDeclaration() {
        val reference = configureAndFindReference(
            """
            target("test")
                on_load(function (target)
                    target:a<caret>dd("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent(),
            expectedName = "add"
        )

        val resolved = reference.resolve() as? XMakeLuaIdentifier
        assertNotNull(resolved)
        assertEquals("add", resolved?.name)
        assertEquals(XMakeApiDeclarationService.SYNTHETIC_FILE_NAME, resolved?.containingFile?.name)
        assertTrue(resolved?.containingFile?.text?.contains("function target:add() end") == true)
    }

    fun testImportedExtensionModuleMemberOwnsReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    import("core.base.json")
                    json.en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )
    }

    fun testAnonymousImportReturnAliasMemberOwnsReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    local json_api = import("core.base.json", {anonymous = true})
                    json_api.en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )
    }

    fun testInheritedExtensionModuleFunctionOwnsReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    import("core.base.json", {inherit = true})
                    en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )
    }

    fun testInheritShorthandExtensionModuleFunctionOwnsReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    inherit("core.base.json")
                    en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )
    }

    fun testAddImportsExtensionModuleMemberOwnsReference() {
        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                add_imports("core.base.json")
                on_load(function (target)
                    json.en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )
    }

    fun testRootDirImportedLocalModuleMemberOwnsReference() {
        myFixture.addFileToProject(
            "modules/hello3.lua",
            """
            function greet()
            end
            """.trimIndent()
        )

        assertReferenceAtCaretOwnsIdentifier(
            """
            target("test")
                on_load(function (target)
                    import("hello3", {rootdir = "modules"})
                    hello3.gr<caret>eet()
                end)
            target_end()
            """.trimIndent(),
            expectedName = "greet"
        )
    }

    fun testRootDirImportedLocalModuleMemberResolvesToLocalDeclaration() {
        myFixture.addFileToProject(
            "modules/hello_decl.lua",
            """
            function greet()
            end
            """.trimIndent()
        )

        val reference = configureAndFindReference(
            """
            target("test")
                on_load(function (target)
                    import("hello_decl", {rootdir = "modules"})
                    hello_decl.gr<caret>eet()
                end)
            target_end()
            """.trimIndent(),
            expectedName = "greet"
        )

        val resolved = reference.resolve() as? XMakeLuaIdentifier
        assertNotNull(resolved)
        assertEquals("greet", resolved?.name)
        assertEquals("hello_decl.lua", resolved?.containingFile?.name)
    }

    fun testImportedExtensionModuleMemberDoesNotResolveAcrossSiblingHooks() {
        val reference = configureAndFindReference(
            """
            target("one")
                on_load(function (target)
                    import("core.base.json")
                end)
            target_end()
            target("two")
                on_load(function (target)
                    json.en<caret>code({})
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )

        assertNull(reference.resolve())
    }

    fun testImportedExtensionModuleMemberDoesNotResolveBeforeImportStatement() {
        val reference = configureAndFindReference(
            """
            target("test")
                on_load(function (target)
                    json.en<caret>code({})
                    import("core.base.json")
                end)
            target_end()
            """.trimIndent(),
            expectedName = "encode"
        )

        assertNull(reference.resolve())
    }

    private fun configureAndFindReference(code: String, expectedName: String? = null): PsiReference {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val identifier = if (expectedName != null) {
            PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
                .asSequence()
                .filter { it.text == expectedName }
                .minByOrNull { candidate ->
                    when {
                        caretOffset in candidate.textRange.startOffset..candidate.textRange.endOffset -> 0
                        caretOffset < candidate.textRange.startOffset -> candidate.textRange.startOffset - caretOffset
                        else -> caretOffset - candidate.textRange.endOffset
                    }
                }
        } else {
            val leaf = myFixture.file.findElementAt(caretOffset)
                ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
                ?: myFixture.file.findElementAt((caretOffset + 1).coerceAtMost(myFixture.file.textLength - 1))
            PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
                ?: leaf as? XMakeLuaIdentifier
        }
        val reference = identifier?.let {
            provider.getReferencesByElement(it, ProcessingContext()).firstOrNull()
        }
        return requireNotNull(reference) { "Reference not found at caret" }
    }

    private fun assertReferenceAtCaretOwnsIdentifier(code: String, expectedName: String) {
        myFixture.configureByText("xmake.lua", code)
        val caretOffset = myFixture.caretOffset
        val identifier = findIdentifierAtCaret(caretOffset)
        val reference = myFixture.file.findReferenceAt(caretOffset)
            ?: myFixture.file.findReferenceAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("Reference not found at caret")
        val providerReference = provider.getReferencesByElement(identifier, ProcessingContext()).singleOrNull()
            ?: error("Expected exactly one provider reference for identifier at caret")

        assertEquals(expectedName, identifier.text)
        assertSame(identifier, reference.element)
        assertEquals(expectedName, reference.element.text)
        assertEquals(expectedName, reference.canonicalText)
        assertEquals(TextRange(0, expectedName.length), reference.rangeInElement)
        assertSame(identifier, providerReference.element)
        assertEquals(expectedName, providerReference.element.text)
    }

    private fun findIdentifierAtCaret(caretOffset: Int): XMakeLuaIdentifier {
        val leaf = myFixture.file.findElementAt(caretOffset)
            ?: myFixture.file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
        return requireNotNull(PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false) ?: leaf as? XMakeLuaIdentifier)
    }
}

