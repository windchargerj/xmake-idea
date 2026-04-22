package io.xmake.lang.analysis.lua

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

internal data class LuaMemberAccess(
    val receiver: XMakeLuaIdentifier,
    val separator: String
)

internal data class LuaIncompleteMemberAccess(
    val receiverPath: String,
    val receiver: XMakeLuaIdentifier?,
    val separator: String,
    val receiverEndOffset: Int
)

internal object LuaMemberAccessResolver {
    private val LINE_BREAKS = charArrayOf('\n', '\r')

    fun findDirectMemberAccess(element: XMakeLuaIdentifier): LuaMemberAccess? {
        val functionCall = PsiTreeUtil.getParentOfType(element, LuaFunctionCall::class.java) ?: return null
        val (receiver, separator) = PsiTreeUtil.findChildrenOfType(functionCall, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textOffset < element.textOffset }
            .sortedByDescending { it.textOffset }
            .firstNotNullOfOrNull { candidate ->
                LuaCallChainResolver.directMemberSeparator(candidate, element, functionCall)
                    ?.let { separator -> candidate to separator }
            }
            ?: return null

        return LuaMemberAccess(receiver, separator)
    }

    fun findIncompleteMemberAccess(element: XMakeLuaIdentifier): LuaIncompleteMemberAccess? {
        val file = element.containingFile ?: return null
        val fileText = file.text
        // Heuristic boundary: completion/typing PSI can be incomplete, so recover the receiver from text.
        val access = LuaLexicalTextSupport.detectMemberAccess(
            text = fileText,
            caretOffset = element.textRange.endOffset
        ) ?: return null

        val memberStartOffset = access.receiverEndOffset + 1
        if (element.textRange.startOffset != memberStartOffset) {
            return null
        }

        if (!isStandaloneLineMemberAccess(fileText, access.receiverStartOffset, element.textRange.endOffset)) {
            return null
        }

        val separatorOffset = access.receiverEndOffset
        val receiverLeaf = file.findElementAt((separatorOffset - 1).coerceAtLeast(0))
        val receiver = receiverLeaf?.let { leaf ->
            PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false) ?: leaf as? XMakeLuaIdentifier
        }?.takeIf { it.textRange.endOffset <= separatorOffset }

        return LuaIncompleteMemberAccess(
            receiverPath = access.receiverPath,
            receiver = receiver,
            separator = access.separator.toString(),
            receiverEndOffset = access.receiverEndOffset
        )
    }

    private fun isStandaloneLineMemberAccess(
        text: String,
        receiverStartOffset: Int,
        memberEndOffset: Int
    ): Boolean {
        val lineStart = findLineStart(text, receiverStartOffset)
        if (text.substring(lineStart, receiverStartOffset).isNotBlank()) {
            return false
        }

        val lineEnd = findLineEnd(text, memberEndOffset)
        val trailing = text.substring(memberEndOffset, lineEnd).trimStart()
        return trailing.isEmpty() || trailing.startsWith("--")
    }

    private fun findLineStart(text: String, offset: Int): Int {
        val clampedOffset = offset.coerceIn(0, text.length)
        return text.lastIndexOfAny(LINE_BREAKS, startIndex = clampedOffset - 1) + 1
    }

    private fun findLineEnd(text: String, offset: Int): Int {
        val clampedOffset = offset.coerceIn(0, text.length)
        return text.indexOfAny(LINE_BREAKS, startIndex = clampedOffset)
            .takeIf { it >= 0 }
            ?: text.length
    }
}
