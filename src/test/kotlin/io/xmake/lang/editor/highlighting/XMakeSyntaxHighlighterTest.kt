package io.xmake.lang.editor.highlighting

class XMakeSyntaxHighlighterTest : XMakeSyntaxHighlighterTestCase() {

    fun testKeywords() = syntaxCases("keywords") {
        "if" expects "DEFAULT_KEYWORD"
        "then" expects "DEFAULT_KEYWORD"
        "end" expects "DEFAULT_KEYWORD"
        "else" expects "DEFAULT_KEYWORD"
        "local" expects "DEFAULT_KEYWORD"
        "function" expects "DEFAULT_KEYWORD"
        "return" expects "DEFAULT_KEYWORD"
        "elseif" expects "DEFAULT_KEYWORD"
        "do" expects "DEFAULT_KEYWORD"
        "while" expects "DEFAULT_KEYWORD"
        "for" expects "DEFAULT_KEYWORD"
        "repeat" expects "DEFAULT_KEYWORD"
        "until" expects "DEFAULT_KEYWORD"
        "break" expects "DEFAULT_KEYWORD"
        "goto" expects "DEFAULT_KEYWORD"
        "in" expects "DEFAULT_KEYWORD"
        "and" expects "DEFAULT_KEYWORD"
        "or" expects "DEFAULT_KEYWORD"
        "not" expects "DEFAULT_KEYWORD"
        "true" expects "DEFAULT_KEYWORD"
        "false" expects "DEFAULT_KEYWORD"
        "nil" expects "DEFAULT_KEYWORD"
    }

    fun testStrings() = syntaxCases("strings") {
        "\"hello\"" expects "DEFAULT_STRING"
        "'hello'" expects "DEFAULT_STRING"
    }

    fun testNumbers() = syntaxCases("numbers") {
        "42" expects "DEFAULT_NUMBER"
        "3.14" expects "DEFAULT_NUMBER"
        "1e10" expects "DEFAULT_NUMBER"
        "0xFF" expects "DEFAULT_NUMBER"
        "0x1.0p0" expects "DEFAULT_NUMBER"
    }

    fun testComments() = syntaxCases("comments") {
        "-- comment" expects "DEFAULT_LINE_COMMENT"
        "#!/usr/bin/lua" expects "DEFAULT_LINE_COMMENT"
    }

    fun testOperators() = syntaxCases("operators") {
        "+" expects "DEFAULT_OPERATION_SIGN"
        "-" expects "DEFAULT_OPERATION_SIGN"
        "*" expects "DEFAULT_OPERATION_SIGN"
        "/" expects "DEFAULT_OPERATION_SIGN"
        "%" expects "DEFAULT_OPERATION_SIGN"
        "^" expects "DEFAULT_OPERATION_SIGN"
        "==" expects "DEFAULT_OPERATION_SIGN"
        "~=" expects "DEFAULT_OPERATION_SIGN"
        "<=" expects "DEFAULT_OPERATION_SIGN"
        ">=" expects "DEFAULT_OPERATION_SIGN"
        "<" expects "DEFAULT_OPERATION_SIGN"
        ">" expects "DEFAULT_OPERATION_SIGN"
        "&" expects "DEFAULT_OPERATION_SIGN"
        "|" expects "DEFAULT_OPERATION_SIGN"
        "~" expects "DEFAULT_OPERATION_SIGN"
        ".." expects "DEFAULT_OPERATION_SIGN"
        "..." expects "DEFAULT_OPERATION_SIGN"
        "::" expects "DEFAULT_OPERATION_SIGN"
        "//" expects "DEFAULT_OPERATION_SIGN"
        "<<" expects "DEFAULT_OPERATION_SIGN"
        ">>" expects "DEFAULT_OPERATION_SIGN"
    }

    fun testBrackets() = syntaxCases("brackets") {
        "{" expects "DEFAULT_BRACKETS"
        "}" expects "DEFAULT_BRACKETS"
        "[" expects "DEFAULT_BRACKETS"
        "]" expects "DEFAULT_BRACKETS"
    }

    fun testSeparatorsAndPunctuation() = syntaxCases("separators-and-punctuation") {
        "," expects "DEFAULT_COMMA"
        ";" expects "DEFAULT_COMMA"
        "." expects "DEFAULT_DOT"
        ":" expects "DEFAULT_OPERATION_SIGN"
        "#" expects "DEFAULT_OPERATION_SIGN"
    }

    fun testBadCharacter() = syntaxCases("bad-character") {
        "@" expects "BAD_CHARACTER"
    }
}
