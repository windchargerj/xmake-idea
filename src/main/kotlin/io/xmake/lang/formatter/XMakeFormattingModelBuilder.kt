package io.xmake.lang.formatter

import com.intellij.formatting.*
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.TokenSet
import io.xmake.lang.XMakeLuaLanguage
import io.xmake.lang.antlr.LuaLexer
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val codeStyleSettings = formattingContext.codeStyleSettings

        return FormattingModelProvider
            .createFormattingModelForPsiFile(
                formattingContext.containingFile,
                XMakeLuaBlock(
                    formattingContext.node,
                    Wrap.createWrap(WrapType.NONE, false),
                    Alignment.createAlignment(),
                    createSpaceBuilder(codeStyleSettings)
                ),
                codeStyleSettings
            )
    }

    companion object {
        // Helper function to create TokenSet
        private fun tokenSet(vararg tokens: Int): TokenSet =
            PSIElementTypeFactory.createTokenSet(XMakeLuaLanguage.INSTANCE, *tokens)

        // Token sets for operators
        private val ASSIGNMENT_OPERATORS = tokenSet(LuaLexer.EQ)
        private val ADDITIVE_OPERATORS = tokenSet(LuaLexer.PLUS, LuaLexer.MINUS)
        private val MULTIPLICATIVE_OPERATORS = tokenSet(LuaLexer.STAR, LuaLexer.SLASH, LuaLexer.SS, LuaLexer.PER)
        private val RELATIONAL_OPERATORS = tokenSet(LuaLexer.LT, LuaLexer.GT, LuaLexer.LE, LuaLexer.GE)
        private val EQUALITY_OPERATORS = tokenSet(LuaLexer.EE, LuaLexer.SQEQ)
        private val LOGICAL_OPERATORS = tokenSet(LuaLexer.AND, LuaLexer.OR)
        private val UNARY_OPERATORS = tokenSet(LuaLexer.NOT, LuaLexer.POUND, LuaLexer.SQUIG)
        private val CONCAT_OPERATOR = tokenSet(LuaLexer.DD)
        private val BITWISE_OPERATORS = tokenSet(LuaLexer.AMP, LuaLexer.PIPE, LuaLexer.SQUIG, LuaLexer.LL, LuaLexer.GG, LuaLexer.CARET)

        // Punctuation
        private val COMMA = tokenSet(LuaLexer.COMMA)
        private val OPEN_PAREN = tokenSet(LuaLexer.OP)
        private val CLOSE_PAREN = tokenSet(LuaLexer.CP)
        private val OPEN_BRACKET = tokenSet(LuaLexer.OB)
        private val CLOSE_BRACKET = tokenSet(LuaLexer.CB)
        private val OPEN_BRACE = tokenSet(LuaLexer.OCU)
        private val CLOSE_BRACE = tokenSet(LuaLexer.CCU)

        // Keywords
        private val KEYWORDS_BEFORE_PAREN = tokenSet(LuaLexer.IF, LuaLexer.ELSEIF, LuaLexer.WHILE, LuaLexer.FOR, LuaLexer.FUNCTION)

        private fun createSpaceBuilder(settings: CodeStyleSettings): SpacingBuilder {
            val commonSettings = settings.getCommonSettings(XMakeLuaLanguage.INSTANCE)

            return SpacingBuilder(settings, XMakeLuaLanguage.INSTANCE)
                // Assignment operators: =
                .around(ASSIGNMENT_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_ASSIGNMENT_OPERATORS)

                // Additive operators: + -
                .around(ADDITIVE_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS)

                // Multiplicative operators: * / // %
                .around(MULTIPLICATIVE_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS)

                // Relational operators: < > <= >=
                .around(RELATIONAL_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_RELATIONAL_OPERATORS)

                // Equality operators: == ~=
                .around(EQUALITY_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_EQUALITY_OPERATORS)

                // Logical operators: and or
                .around(LOGICAL_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_LOGICAL_OPERATORS)

                // Concatenation operator: ..
                .around(CONCAT_OPERATOR)
                .spaceIf(commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS)

                // Bitwise operators: & | ~ << >> ^
                .around(BITWISE_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_BITWISE_OPERATORS)

                // Comma
                .after(COMMA)
                .spaceIf(commonSettings.SPACE_AFTER_COMMA)
                .before(COMMA)
                .spaceIf(commonSettings.SPACE_BEFORE_COMMA)

                // Parentheses
                .after(OPEN_PAREN)
                .spaceIf(commonSettings.SPACE_WITHIN_PARENTHESES)
                .before(CLOSE_PAREN)
                .spaceIf(commonSettings.SPACE_WITHIN_PARENTHESES)

                // Brackets
                .after(OPEN_BRACKET)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACKETS)
                .before(CLOSE_BRACKET)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACKETS)

                // Braces
                .after(OPEN_BRACE)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACES)
                .before(CLOSE_BRACE)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACES)
        }
    }
}
