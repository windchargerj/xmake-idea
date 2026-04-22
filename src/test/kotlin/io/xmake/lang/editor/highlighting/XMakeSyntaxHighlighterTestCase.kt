package io.xmake.lang.editor.highlighting

import com.intellij.lexer.Lexer
import com.intellij.testFramework.EditorTestUtil
import io.xmake.lang.XMakeTestCase
import java.io.File

abstract class XMakeSyntaxHighlighterTestCase : XMakeTestCase() {

    private val highlighter: XMakeLuaSyntaxHighlighter by lazy { XMakeLuaSyntaxHighlighter() }

    protected fun syntaxCases(group: String, block: SyntaxCaseGroup.() -> Unit) {
        SyntaxCaseGroup(group).apply(block)
    }

    protected inner class SyntaxCaseGroup(private val group: String) {
        infix fun String.expects(expectedKey: String) {
            try {
                testSyntaxHighlighting(this, expectedKey)
            } catch (error: AssertionError) {
                throw AssertionError(
                    "Syntax highlighting case failed in $group for `$this` -> $expectedKey: ${error.message}",
                    error
                )
            }
        }

        fun case(code: String, vararg expectedKeys: String) {
            try {
                testSyntaxHighlighting(code, *expectedKeys)
            } catch (error: AssertionError) {
                throw AssertionError(
                    "Syntax highlighting case failed in $group for `$code` -> ${expectedKeys.toList()}: ${error.message}",
                    error
                )
            }
        }
    }

    private fun testSyntaxHighlighting(code: String, vararg expectedKeys: String) {
        val answerFile = createAnswerFile(code, expectedKeys.toList())

        try {
            myFixture.configureByText("xmake.lua", code)
            EditorTestUtil.testFileSyntaxHighlighting(
                myFixture.file,
                answerFile.absolutePath,
                false
            )
        } finally {
            answerFile.delete()
        }
    }

    private fun createAnswerFile(code: String, expectedKeys: List<String>): File {
        val tokens = tokenize(code)

        val sb = StringBuilder()
        tokens.forEachIndexed { index, token ->
            val escapedToken = token.replace(" ", "␣")
            sb.append(escapedToken)
            sb.append("\n")
            if (index < expectedKeys.size && expectedKeys[index].isNotEmpty()) {
                sb.append("    ")
                sb.append(expectedKeys[index])
                sb.append("\n")
            }
        }

        val file = File.createTempFile("highlight_", ".txt")
        file.writeText(sb.toString())
        return file
    }

    private fun tokenize(code: String): List<String> {
        val tokens = mutableListOf<String>()
        val lexer: Lexer = highlighter.highlightingLexer
        lexer.start(code)

        while (lexer.tokenType != null) {
            val tokenText = code.substring(lexer.tokenStart, lexer.tokenEnd)
            if (tokenText.isNotBlank()) {
                tokens.add(tokenText)
            }
            lexer.advance()
        }

        return tokens
    }
}
