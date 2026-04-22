package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaString
import io.xmake.lang.syntax.psi.lua.LuaTableConstructor
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

internal object CompletionSyntaxContext {
    private const val COMPLETION_PLACEHOLDER = "IntellijIdeaRulezzz"
    private val importFunctions = setOf("import", "inherit", "add_imports")

    fun detectImportPrefix(parameters: CompletionParameters): String? =
        LuaLexicalTextSupport.detectCallStringPrefix(
            text = parameters.editor.document.text,
            caretOffset = parameters.editor.caretModel.offset,
            functionNames = importFunctions
        )

    fun hasMemberAccessSyntax(parameters: CompletionParameters): Boolean =
        // Heuristic boundary: completion dispatch asks the live document before a stable PSI call exists.
        LuaLexicalTextSupport.detectMemberAccess(
            parameters.editor.document.text,
            parameters.editor.caretModel.offset
        ) != null

    fun extractImportModulePrefix(luaString: LuaString): String? =
        LuaLexicalTextSupport.extractQuotedPrefix(
            luaStringText = luaString.text,
            completionPlaceholder = COMPLETION_PLACEHOLDER
        )

    fun isInsideImportString(position: PsiElement): Boolean =
        findImportPathString(position) != null

    fun isInsideStringLiteral(position: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(position, LuaString::class.java, false) != null

    fun isInsideTableConstructor(position: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(position, LuaTableConstructor::class.java, false) != null

    fun shouldAllowIdentifierCompletionInTable(position: PsiElement): Boolean {
        val identifier = position as? XMakeLuaIdentifier
            ?: PsiTreeUtil.getParentOfType(position, XMakeLuaIdentifier::class.java, false)
            ?: return false
        return !PsiPredicates.isTableKey(identifier)
    }

    fun findImportPathString(position: PsiElement): LuaString? {
        val luaString = PsiTreeUtil.getParentOfType(position, LuaString::class.java, false) ?: return null
        val functionCall = PsiTreeUtil.getParentOfType(luaString, LuaFunctionCall::class.java) ?: return null
        val functionName = functionCall.children.firstOrNull { it is XMakeLuaIdentifier }?.text
        if (functionName !in importFunctions) {
            return null
        }

        if (functionName == "add_imports") {
            return luaString
        }

        val importPathString = PsiTreeUtil.findChildrenOfType(functionCall, LuaString::class.java).firstOrNull()
        return luaString.takeIf { it == importPathString }
    }

    fun isInsideComment(parameters: CompletionParameters): Boolean {
        val offset = parameters.offset
        val document = parameters.editor.document
        val text = document.text
        val lineNumber = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val textBeforeCaret = text.substring(lineStartOffset, offset)
        if (LuaLexicalTextSupport.isInSingleLineComment(textBeforeCaret)) {
            return true
        }
        return LuaLexicalTextSupport.isInsideBlockComment(text, offset)
    }
}
