package io.xmake.lang.editor.formatting

import com.intellij.formatting.*
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.TokenSet
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.syntax.XMakeLuaLanguage
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory

class XMakeFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val codeStyleSettings = formattingContext.codeStyleSettings
        val commonSettings = codeStyleSettings.getCommonSettings(XMakeLuaLanguage.INSTANCE)

        return FormattingModelProvider
            .createFormattingModelForPsiFile(
                formattingContext.containingFile,
                XMakeLuaBlock(
                    formattingContext.node,
                    Wrap.createWrap(WrapType.NONE, false),
                    Alignment.createAlignment(),
                    commonSettings,
                    createSpaceBuilder(codeStyleSettings, commonSettings)
                ),
                codeStyleSettings
            )
    }

    companion object {
        private fun tokenSet(vararg tokens: Int): TokenSet =
            PSIElementTypeFactory.createTokenSet(XMakeLuaLanguage.INSTANCE, *tokens)

        private val ASSIGNMENT_OPERATORS = tokenSet(LuaLexer.EQ)
        private val MULTIPLICATIVE_OPERATORS = tokenSet(LuaLexer.STAR, LuaLexer.SLASH, LuaLexer.SS, LuaLexer.PER, LuaLexer.CARET)
        private val RELATIONAL_OPERATORS = tokenSet(LuaLexer.LT, LuaLexer.GT, LuaLexer.LE, LuaLexer.GE)
        private val EQUALITY_OPERATORS = tokenSet(LuaLexer.EE, LuaLexer.SQEQ)
        private val LOGICAL_OPERATORS = tokenSet(LuaLexer.AND, LuaLexer.OR)
        private val CONCAT_OPERATOR = tokenSet(LuaLexer.DD)
        private val BITWISE_OPERATORS = tokenSet(LuaLexer.AMP, LuaLexer.PIPE, LuaLexer.LL, LuaLexer.GG)

        private val COMMA = tokenSet(LuaLexer.COMMA)
        private val OPEN_PAREN = tokenSet(LuaLexer.OP)
        private val CLOSE_PAREN = tokenSet(LuaLexer.CP)
        private val OPEN_BRACKET = tokenSet(LuaLexer.OB)
        private val CLOSE_BRACKET = tokenSet(LuaLexer.CB)
        private val OPEN_BRACE = tokenSet(LuaLexer.OCU)
        private val CLOSE_BRACE = tokenSet(LuaLexer.CCU)

        private fun createSpaceBuilder(
            settings: CodeStyleSettings,
            commonSettings: CommonCodeStyleSettings
        ): SpacingBuilder {
            return SpacingBuilder(settings, XMakeLuaLanguage.INSTANCE)
                .around(ASSIGNMENT_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_ASSIGNMENT_OPERATORS)

                .around(MULTIPLICATIVE_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS)

                .around(RELATIONAL_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_RELATIONAL_OPERATORS)

                .around(EQUALITY_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_EQUALITY_OPERATORS)

                .around(LOGICAL_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_LOGICAL_OPERATORS)

                .around(CONCAT_OPERATOR)
                .spaceIf(commonSettings.SPACE_AROUND_ADDITIVE_OPERATORS)

                .around(BITWISE_OPERATORS)
                .spaceIf(commonSettings.SPACE_AROUND_BITWISE_OPERATORS)

                .after(COMMA)
                .spaceIf(commonSettings.SPACE_AFTER_COMMA)
                .before(COMMA)
                .spaceIf(commonSettings.SPACE_BEFORE_COMMA)

                .after(OPEN_PAREN)
                .spaceIf(commonSettings.SPACE_WITHIN_PARENTHESES)
                .before(CLOSE_PAREN)
                .spaceIf(commonSettings.SPACE_WITHIN_PARENTHESES)

                .after(OPEN_BRACKET)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACKETS)
                .before(CLOSE_BRACKET)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACKETS)

                .after(OPEN_BRACE)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACES)
                .before(CLOSE_BRACE)
                .spaceIf(commonSettings.SPACE_WITHIN_BRACES)
        }
    }
}
