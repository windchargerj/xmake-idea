package io.xmake.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.antlr.LuaParser
import io.xmake.lang.psi.XMakeLuaFile
import io.xmake.lang.psi.lua.*
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.GlobalScope
import io.xmake.lang.psi.xmake.NamespaceScope
import io.xmake.lang.psi.xmake.ScriptScope
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.lexer.TokenIElementType
import org.antlr.intellij.adaptor.parser.ANTLRParseTreeToPSIConverter
import org.antlr.intellij.adaptor.parser.ANTLRParserAdaptor
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.tree.ParseTree

class XMakeLuaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project): Lexer {
        val lexer = LuaLexer(null)
        return ANTLRLexerAdaptor(XMakeLuaLanguage.INSTANCE, lexer)
    }

    override fun createParser(project: Project): PsiParser {
        val parser = LuaParser(null)
        return object : ANTLRParserAdaptor(XMakeLuaLanguage.INSTANCE, parser) {
            override fun parse(parser: Parser, root: IElementType): ParseTree {
                // start rule depends on root passed in; sometimes we want to create an ID node etc...
                if (root is IFileElementType) {
                    return (parser as LuaParser).start_()
                }
                if (root is RuleIElementType && root.ruleIndex == LuaParser.RULE_functiondef)
                    return (parser as LuaParser).functiondef()
                // let's hope it's an ID as needed by "rename function"
                return (parser as LuaParser).getInvokingContext(root.index.toInt())
            }

            override fun createListener(
                parser: Parser,
                root: IElementType,
                builder: PsiBuilder
            ): ANTLRParseTreeToPSIConverter {
                return XMakeLuaParseTreeToPSIConverter(language, parser, builder)
            }
        }
    }

    /** "Tokens of those types are automatically skipped by PsiBuilder."  */
    override fun getWhitespaceTokens(): TokenSet {
        return WHITESPACE
    }

    override fun getCommentTokens(): TokenSet {
        return COMMENTS
    }

    override fun getStringLiteralElements(): TokenSet {
        return STRING
    }

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements {
        return ParserDefinition.SpaceRequirements.MAY
    }

    override fun getFileNodeType(): IFileElementType {
        return FILE
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile {
        return XMakeLuaFile(viewProvider)
    }

    override fun createElement(node: ASTNode): PsiElement {
        return when (val elType = node.elementType) {
            is XMakeLanguageIElementTypes.DescriptionScopeType ->
                if (elType.typeName == null) GlobalScope(node) else DomainScope(node, elType.typeName)
            is XMakeLanguageIElementTypes.ScriptScopeType ->
                ScriptScope(node)
            is XMakeLanguageIElementTypes.NamespaceScopeType ->
                NamespaceScope(node)
            is TokenIElementType ->
                ANTLRPsiNode(node)
            !is RuleIElementType ->
                ANTLRPsiNode(node)
            else ->
                when (elType.ruleIndex) {
                    LuaParser.RULE_chunk -> LuaChunk(node)
                    LuaParser.RULE_block -> LuaBlock(node)
                    LuaParser.RULE_stat -> LuaStatement(node)
                    LuaParser.RULE_attnamelist -> LuaAttributeNameList(node)
                    LuaParser.RULE_tableconstructor -> LuaTableConstructor(node)
                    LuaParser.RULE_varlist -> LuaVariableList(node)
                    LuaParser.RULE_var -> LuaVariableDefinition(node, elType)
                    LuaParser.RULE_functiondef -> LuaFunctionDefinition(node, elType)
                    LuaParser.RULE_funcname -> LuaFunctionName(node)
                    LuaParser.RULE_parlist -> LuaParameterList(node)
                    LuaParser.RULE_funcbody -> LuaFunctionBody(node)
                    LuaParser.RULE_retstat -> LuaReturnStatement(node)
                    LuaParser.RULE_functioncall -> LuaFunctionCall(node)
                    LuaParser.RULE_args -> LuaArgs(node)
                    LuaParser.RULE_string -> LuaString(node)
                    LuaParser.RULE_explist -> LuaExpressionList(node)
                    LuaParser.RULE_exp -> LuaExpression(node)
                    LuaParser.RULE_fieldlist -> LuaFieldList(node)
                    else -> ANTLRPsiNode(node)
                }
        }
    }

    companion object {
        val logger = logger<XMakeLuaParserDefinition>()
    }
}

val FILE: IFileElementType = IFileElementType(XMakeLuaLanguage.INSTANCE)

lateinit var ID: TokenIElementType

private val COMMENTS: TokenSet = PSIElementTypeFactory.createTokenSet(
    XMakeLuaLanguage.INSTANCE,
    LuaLexer.COMMENT, LuaLexer.SHEBANG
)

private val WHITESPACE: TokenSet = PSIElementTypeFactory.createTokenSet(
    XMakeLuaLanguage.INSTANCE,
    LuaLexer.WS
)

private val STRING: TokenSet = PSIElementTypeFactory.createTokenSet(
    XMakeLuaLanguage.INSTANCE,
    LuaLexer.NORMALSTRING, LuaLexer.CHARSTRING, LuaLexer.LONGSTRING
)
