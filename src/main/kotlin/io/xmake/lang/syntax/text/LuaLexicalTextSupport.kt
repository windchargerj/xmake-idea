package io.xmake.lang.syntax.text

internal object LuaLexicalTextSupport {

    data class MemberAccess(
        val receiverPath: String,
        val separator: Char,
        val receiverStartOffset: Int,
        val receiverEndOffset: Int
    )

    data class MemberAccessSyntax(
        val separator: Char,
        val separatorOffset: Int
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
        val memberAccessSyntax = detectMemberAccessSyntax(text, caretOffset) ?: return null
        val separatorIndex = memberAccessSyntax.separatorOffset
        val separator = memberAccessSyntax.separator

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

    fun detectMemberAccessSyntax(text: String, caretOffset: Int): MemberAccessSyntax? {
        val separatorIndex = findMemberAccessSeparatorIndex(text, caretOffset) ?: return null
        return MemberAccessSyntax(
            separator = text[separatorIndex],
            separatorOffset = separatorIndex
        )
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

        var index = 0
        while (index < caretOffset) {
            val current = text[index]
            when {
                current == '"' || current == '\'' -> {
                    index = skipQuotedString(text, index)
                }

                current == '[' -> {
                    val longString = parseLongBracketOpener(text, index)
                    index = if (longString != null) {
                        val closerStart = findLongBracketCloser(text, longString)
                        if (closerStart == null) {
                            return false
                        }
                        closerStart + longString.closerLength
                    } else {
                        index + 1
                    }
                }

                isLineCommentStart(text, index) -> {
                    val longComment = parseLongCommentOpener(text, index)
                    if (longComment != null) {
                        val closerStart = findLongBracketCloser(text, longComment)
                        if (closerStart == null || caretOffset <= closerStart) {
                            return true
                        }
                        index = closerStart + longComment.closerLength
                    } else {
                        index = indexOfNextLine(text, index + 2)
                    }
                }

                else -> index++
            }
        }
        return false
    }

    private data class LongBracket(
        val equalsCount: Int,
        val contentStartOffset: Int
    ) {
        val closerLength: Int
            get() = equalsCount + 2
    }

    private fun parseLongCommentOpener(text: String, startIndex: Int): LongBracket? {
        if (!isLineCommentStart(text, startIndex)) {
            return null
        }
        return parseLongBracketOpener(text, startIndex + 2)
    }

    private fun parseLongBracketOpener(text: String, startIndex: Int): LongBracket? {
        if (startIndex >= text.length || text[startIndex] != '[') {
            return null
        }

        var index = startIndex + 1
        while (index < text.length && text[index] == '=') {
            index++
        }
        if (index >= text.length || text[index] != '[') {
            return null
        }
        return LongBracket(
            equalsCount = index - startIndex - 1,
            contentStartOffset = index + 1
        )
    }

    private fun findLongBracketCloser(text: String, bracket: LongBracket): Int? {
        val closingBracket = buildString {
            append(']')
            repeat(bracket.equalsCount) { append('=') }
            append(']')
        }
        return text.indexOf(closingBracket, bracket.contentStartOffset).takeIf { it >= 0 }
    }

    private fun skipQuotedString(text: String, startIndex: Int): Int {
        val quote = text[startIndex]
        var index = startIndex + 1
        while (index < text.length) {
            if (text[index] == quote && !isEscaped(text, index)) {
                return index + 1
            }
            index++
        }
        return text.length
    }

    private fun indexOfNextLine(text: String, startIndex: Int): Int {
        val newlineIndex = text.indexOfAny(charArrayOf('\n', '\r'), startIndex)
        return if (newlineIndex >= 0) newlineIndex + 1 else text.length
    }

    private fun isLineCommentStart(text: String, index: Int): Boolean =
        text[index] == '-' && index + 1 < text.length && text[index + 1] == '-'

    private fun findMemberAccessSeparatorIndex(text: String, caretOffset: Int): Int? {
        if (caretOffset <= 0 || caretOffset > text.length) {
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
        if (separator == '.' && text.hasNeighbor(separatorIndex, '.')) {
            return null
        }
        if (separator == ':' && text.hasNeighbor(separatorIndex, ':')) {
            return null
        }
        return separatorIndex
    }

    private fun String.hasNeighbor(index: Int, char: Char): Boolean =
        (index > 0 && this[index - 1] == char) ||
            (index + 1 < length && this[index + 1] == char)

    private fun isIdentifierPart(char: Char): Boolean =
        char == '_' || char.isLetterOrDigit()

    private fun isMemberAccessReceiverPart(char: Char): Boolean =
        isIdentifierPart(char) || char == '.' || char == ':'

}
