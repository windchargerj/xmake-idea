package io.xmake.lang.syntax.text

internal object LuaLexicalTextSupport {

    data class MemberAccess(
        val receiverPath: String,
        val separator: Char,
        val receiverStartOffset: Int,
        val receiverEndOffset: Int
    )

    fun detectCallStringPrefix(
        text: String,
        caretOffset: Int,
        functionNames: Set<String>
    ): String? {
        if (caretOffset <= 0 || caretOffset > text.length) {
            return null
        }

        val quoteIndex = findOpeningQuote(text, caretOffset) ?: return null
        val prefix = text.substring(quoteIndex + 1, caretOffset).trimEnd().ifBlank { return null }

        var index = skipWhitespaceBackward(text, quoteIndex - 1)
        if (index < 0 || text[index] != '(') {
            return null
        }

        index = skipWhitespaceBackward(text, index - 1)
        if (index < 0) {
            return null
        }

        val end = index
        while (index >= 0 && isIdentifierPart(text[index])) {
            index--
        }
        val start = index + 1
        if (start > end) {
            return null
        }

        val functionName = text.substring(start, end + 1)
        return prefix.takeIf { functionName in functionNames }
    }

    fun detectMemberAccess(text: String, caretOffset: Int): MemberAccess? {
        // Heuristic boundary: this is a lexical recovery path for incomplete editor text, not Lua parsing.
        if (caretOffset <= 0) {
            return null
        }

        var separatorIndex = caretOffset - 1
        while (separatorIndex >= 0 && isIdentifierPart(text[separatorIndex])) {
            separatorIndex--
        }

        if (separatorIndex < 0) {
            return null
        }

        val separator = text[separatorIndex]
        if (separator != '.' && separator != ':') {
            return null
        }

        var start = separatorIndex - 1
        while (start >= 0 && isMemberAccessReceiverPart(text[start])) {
            start--
        }

        val receiver = text.substring(start + 1, separatorIndex)
        return receiver.ifEmpty { null }?.let {
            MemberAccess(
                receiverPath = it,
                separator = separator,
                receiverStartOffset = start + 1,
                receiverEndOffset = separatorIndex
            )
        }
    }

    fun isInSingleLineComment(textBeforeCaret: String): Boolean {
        var inString: Char? = null
        var escaped = false
        textBeforeCaret.forEachIndexed { index, current ->
            if (inString != null) {
                when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == inString -> inString = null
                }
                return@forEachIndexed
            }

            when {
                current == '"' || current == '\'' -> inString = current
                isLineCommentStart(textBeforeCaret, index) -> return true
            }
        }
        return false
    }

    fun extractQuotedPrefix(
        luaStringText: String,
        completionPlaceholder: String? = null
    ): String? {
        if (luaStringText.length < 2) {
            return null
        }

        val unquoted = when {
            luaStringText.startsWith("'") && luaStringText.endsWith("'") -> luaStringText.drop(1).dropLast(1)
            luaStringText.startsWith("\"") && luaStringText.endsWith("\"") -> luaStringText.drop(1).dropLast(1)
            luaStringText.startsWith("'") -> luaStringText.drop(1)
            luaStringText.startsWith("\"") -> luaStringText.drop(1)
            else -> luaStringText
        }

        val cleaned = if (completionPlaceholder != null) {
            Regex("${Regex.escape(completionPlaceholder)}\\S*").replace(unquoted, "")
        } else {
            unquoted
        }.trimEnd()

        return cleaned.ifBlank { null }
    }

    private fun findOpeningQuote(text: String, caretOffset: Int): Int? {
        var index = caretOffset - 1
        while (index >= 0) {
            val current = text[index]
            if (current == '\n' || current == '\r') {
                return null
            }
            if ((current == '"' || current == '\'') && !isEscaped(text, index)) {
                return index
            }
            index--
        }
        return null
    }

    fun isEscaped(text: CharSequence, index: Int): Boolean {
        if (index <= 0 || index > text.lastIndex) {
            return false
        }

        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashCount++
            cursor--
        }
        return slashCount % 2 == 1
    }

    private fun skipWhitespaceBackward(text: String, startIndex: Int): Int {
        var index = startIndex
        while (index >= 0 && text[index].isWhitespace()) {
            index--
        }
        return index
    }

    fun isInsideBlockComment(text: String, caretOffset: Int): Boolean {
        if (caretOffset <= 0 || caretOffset > text.length) return false

        // Find the last long bracket opener before the caret
        val openerStart = findLastLongBracketOpener(text, caretOffset) ?: return false

        // Count equals signs in the opener to determine delimiter level
        var eqCount = 0
        var i = openerStart + 1
        while (i < text.length && text[i] == '=') {
            eqCount++
            i++
        }
        // openerStart+eqCount+1 should be '['
        if (i >= text.length || text[i] != '[') return false

        // Build the matching closing bracket
        val closingBracket = buildString {
            append(']')
            repeat(eqCount) { append('=') }
            append(']')
        }
        val closerStart = text.indexOf(closingBracket, i + 1)
        return closerStart >= caretOffset
    }

    private fun findLastLongBracketOpener(text: String, beforeOffset: Int): Int? {
        // Look for [[ or [=[ or [==[ etc., or --[[ or --[=[ etc.
        // Search backwards from beforeOffset
        var i = beforeOffset - 1
        while (i >= 0) {
            if (text[i] == '[') {
                // Check if this is a long bracket opener
                val openerStart = if (text.getOrNull(i - 1) == '-') i - 1 else i
                if (isLongBracketOpener(text, openerStart)) {
                    return openerStart
                }
            }
            i--
        }
        return null
    }

    private fun isLongBracketOpener(text: String, startIndex: Int): Boolean {
        if (startIndex < 0) return false
        var i = startIndex
        if (i + 1 < text.length && text[i] == '-' && text[i + 1] == '[') {
            i += 2
        } else if (text[i] == '[') {
            i++
        } else {
            return false
        }
        if (i >= text.length || text[i] != '[') return false
        // Check it's a proper long bracket (at least one = or directly [[)
        while (i + 1 < text.length && text[i] == '=') {
            i++
        }
        return i < text.length && text[i] == '['
    }

    private fun isLineCommentStart(text: String, index: Int): Boolean =
        text[index] == '-' && index + 1 < text.length && text[index + 1] == '-'

    private fun isIdentifierPart(char: Char): Boolean =
        char == '_' || char.isLetterOrDigit()

    private fun isMemberAccessReceiverPart(char: Char): Boolean =
        isIdentifierPart(char) || char == '.' || char == ':'

}
