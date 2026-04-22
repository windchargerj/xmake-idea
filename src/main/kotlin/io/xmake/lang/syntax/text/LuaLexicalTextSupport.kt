package io.xmake.lang.syntax.text

internal object LuaLexicalTextSupport {

    data class MemberAccess(
        val receiverPath: String,
        val separator: Char,
        val receiverStartOffset: Int,
        val receiverEndOffset: Int
    )

    private enum class LexicalBlockKind {
        FUNCTION,
        DOMAIN_CONFIGURATION_FUNCTION,
        IF,
        FOR,
        WHILE,
        DO,
        REPEAT;

        fun closesWith(token: String): Boolean = when (this) {
            REPEAT -> token == "until"
            else -> token == "end"
        }
    }

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
            Regex("$completionPlaceholder[^\\s]*").replace(unquoted, "")
        } else {
            unquoted
        }.trimEnd()

        return cleaned.ifBlank { null }
    }

    fun isInOpenFunctionDefinition(
        textBeforeCaret: String,
        domainConfigurationCalls: Set<String>
    ): Boolean {
        val blockStack = ArrayDeque<LexicalBlockKind>()
        val callStack = ArrayDeque<String?>()
        var index = 0
        var inString: Char? = null
        var escaped = false
        var lastIdentifier: String? = null

        while (index < textBeforeCaret.length) {
            val current = textBeforeCaret[index]

            if (inString != null) {
                when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == inString -> inString = null
                }
                index++
                continue
            }

            val blockCommentEnd = skipBlockComment(textBeforeCaret, index)
            val longBracketEnd = skipLongBracket(textBeforeCaret, index)
            when {
                blockCommentEnd != null -> {
                    index = blockCommentEnd
                    lastIdentifier = null
                    continue
                }

                longBracketEnd != null -> {
                    index = longBracketEnd
                    lastIdentifier = null
                    continue
                }

                current == '"' || current == '\'' -> {
                    inString = current
                    lastIdentifier = null
                    index++
                    continue
                }

                isLineCommentStart(textBeforeCaret, index) -> {
                    index = skipLineComment(textBeforeCaret, index + 2)
                    lastIdentifier = null
                    continue
                }

                isIdentifierStart(current) -> {
                    val start = index
                    index = consumeIdentifier(textBeforeCaret, index + 1)
                    val token = textBeforeCaret.substring(start, index)
                    lastIdentifier = handleKeywordToken(token, callStack, blockStack, domainConfigurationCalls) ?: token
                    continue
                }

                current == '(' -> {
                    callStack.addLast(lastIdentifier)
                    lastIdentifier = null
                    index++
                }

                current == ')' -> {
                    if (callStack.isNotEmpty()) {
                        callStack.removeLast()
                    }
                    lastIdentifier = null
                    index++
                }

                else -> index++
            }
        }

        return blockStack.any { it == LexicalBlockKind.FUNCTION }
    }

    private fun handleKeywordToken(
        token: String,
        callStack: ArrayDeque<String?>,
        blockStack: ArrayDeque<LexicalBlockKind>,
        domainConfigurationCalls: Set<String>
    ): String? {
        when (token) {
            "function" -> {
                blockStack.addLast(functionBlockType(callStack.lastOrNull(), domainConfigurationCalls))
                return null
            }

            "if", "for", "while", "do", "repeat" -> {
                blockStack.addLast(keywordBlockType(token))
                return null
            }

            "end", "until" -> {
                blockStack.removeLastIfClosedBy(token)
                return null
            }
        }
        return token
    }

    private fun keywordBlockType(token: String): LexicalBlockKind = when (token) {
        "if" -> LexicalBlockKind.IF
        "for" -> LexicalBlockKind.FOR
        "while" -> LexicalBlockKind.WHILE
        "do" -> LexicalBlockKind.DO
        "repeat" -> LexicalBlockKind.REPEAT
        else -> error("Unsupported block token: $token")
    }

    private fun functionBlockType(
        enclosingCall: String?,
        domainConfigurationCalls: Set<String>
    ): LexicalBlockKind = if (enclosingCall in domainConfigurationCalls) {
        LexicalBlockKind.DOMAIN_CONFIGURATION_FUNCTION
    } else {
        LexicalBlockKind.FUNCTION
    }

    private fun ArrayDeque<LexicalBlockKind>.removeLastIfClosedBy(token: String) {
        val currentBlock = lastOrNull() ?: return
        if (currentBlock.closesWith(token)) {
            removeLast()
        }
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

    private fun isEscaped(text: String, index: Int): Boolean {
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

    private fun skipLineComment(text: String, startIndex: Int): Int {
        var index = startIndex
        while (index < text.length && text[index] != '\n' && text[index] != '\r') {
            index++
        }
        return index
    }

    private fun skipBlockComment(text: String, index: Int): Int? {
        if (!isLineCommentStart(text, index)) {
            return null
        }
        return skipLongBracket(text, index + 2)
    }

    private fun skipLongBracket(text: String, index: Int): Int? {
        if (index >= text.length || text[index] != '[') {
            return null
        }
        var delimiterIndex = index + 1
        while (delimiterIndex < text.length && text[delimiterIndex] == '=') {
            delimiterIndex++
        }
        if (delimiterIndex >= text.length || text[delimiterIndex] != '[') {
            return null
        }

        val closing = "]" + "=".repeat(delimiterIndex - index - 1) + "]"
        val closingIndex = text.indexOf(closing, delimiterIndex + 1)
        return if (closingIndex >= 0) {
            closingIndex + closing.length
        } else {
            text.length
        }
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
        while (i >= 1) {
            if (text[i] == '[' && (text[i - 1] != '-' || text[i - 1] == '-')) {
                // Check if this is a long bracket opener
                val openerStart = if (i >= 1 && text[i - 1] == '-') i - 1 else i
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

    private fun isIdentifierStart(char: Char): Boolean =
        char == '_' || char.isLetter()

    private fun isIdentifierPart(char: Char): Boolean =
        char == '_' || char.isLetterOrDigit()

    private fun isMemberAccessReceiverPart(char: Char): Boolean =
        isIdentifierPart(char) || char == '.' || char == ':'

    private fun consumeIdentifier(text: String, startIndex: Int): Int {
        var index = startIndex
        while (index < text.length && isIdentifierPart(text[index])) {
            index++
        }
        return index
    }
}
