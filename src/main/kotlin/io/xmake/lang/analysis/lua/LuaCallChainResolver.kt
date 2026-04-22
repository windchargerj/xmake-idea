package io.xmake.lang.analysis.lua

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaArgs
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall

internal data class LuaCallChain(
    val pathSegments: List<String>,
    val identifiers: List<XMakeLuaIdentifier>,
    val separators: List<String>,
    val targetIdentifier: XMakeLuaIdentifier
)

internal object LuaCallChainResolver {

    fun resolve(identifier: XMakeLuaIdentifier): LuaCallChain? {
        val functionCall = PsiTreeUtil.getParentOfType(identifier, LuaFunctionCall::class.java) ?: return null
        val args = PsiTreeUtil.findChildOfType(functionCall, LuaArgs::class.java) ?: return null
        val argsStart = args.textRange.startOffset

        val candidates = PsiTreeUtil.findChildrenOfType(functionCall, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textRange.endOffset <= argsStart }
            .sortedBy { it.textOffset }
            .toList()

        if (candidates.size < 2 || identifier !in candidates) {
            return null
        }

        val identifiers = buildChainIdentifiers(candidates, functionCall)
        if (identifiers.size < 2 || identifier !in identifiers) {
            return null
        }

        val separators = mutableListOf<String>()
        identifiers.forEachIndexed { index, current ->
            if (index > 0) {
                val previous = identifiers[index - 1]
                val separatorText = directMemberSeparator(previous, current, functionCall) ?: return null
                separators.add(separatorText)
            }
        }

        return LuaCallChain(
            pathSegments = identifiers.map { it.text },
            identifiers = identifiers,
            separators = separators,
            targetIdentifier = identifiers.last()
        )
    }

    internal fun directMemberSeparator(
        left: XMakeLuaIdentifier,
        right: XMakeLuaIdentifier,
        scope: LuaFunctionCall
    ): String? {
        var leaf = LuaPsiVisibleLeaves.nextWithin(left, scope)
        var separator: String? = null
        var parenthesisDepth = 0
        var bracketDepth = 0
        var braceDepth = 0

        while (leaf != null && leaf != right) {
            when (leaf.text) {
                "(" -> parenthesisDepth++
                ")" -> {
                    if (parenthesisDepth == 0) {
                        return null
                    }
                    parenthesisDepth--
                }

                "[" -> bracketDepth++
                "]" -> {
                    if (bracketDepth == 0) {
                        return null
                    }
                    bracketDepth--
                }

                "{" -> braceDepth++
                "}" -> {
                    if (braceDepth == 0) {
                        return null
                    }
                    braceDepth--
                }

                ".", ":" -> {
                    if (parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        if (separator != null) {
                            return null
                        }
                        separator = leaf.text
                    }
                }

                else -> {
                    if (parenthesisDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                        return null
                    }
                }
            }
            leaf = LuaPsiVisibleLeaves.nextWithin(leaf, scope)
        }

        if (leaf != right || parenthesisDepth != 0 || bracketDepth != 0 || braceDepth != 0) {
            return null
        }

        return separator
    }

    private fun buildChainIdentifiers(
        candidates: List<XMakeLuaIdentifier>,
        scope: LuaFunctionCall
    ): List<XMakeLuaIdentifier> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val chain = mutableListOf(candidates.first())
        var current = candidates.first()

        while (true) {
            val next = candidates.firstOrNull { candidate ->
                candidate.textOffset > current.textOffset &&
                    directMemberSeparator(current, candidate, scope) != null
            } ?: break

            chain += next
            current = next
        }

        return chain
    }
}
