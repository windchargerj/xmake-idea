package io.xmake.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.antlr.LuaParser
import io.xmake.lang.parser.XMakeLuaParserAdaptor
import io.xmake.lang.psi.XMakeLuaFile
import io.xmake.lang.psi.XMakeLuaIdentifier
import io.xmake.lang.psi.lua.*
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.GlobalScope
import io.xmake.lang.psi.xmake.NamespaceScope
import io.xmake.lang.psi.xmake.ScriptScope
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.lexer.TokenIElementType
import org.antlr.intellij.adaptor.psi.ANTLRPsiNode

class XMakeLuaParserDefinition : ParserDefinition {

    override fun createLexer(project: Project): Lexer {
        val lexer = LuaLexer(null)
        return ANTLRLexerAdaptor(XMakeLuaLanguage.INSTANCE, lexer)
    }

    override fun createParser(project: Project): PsiParser {
        val parser = LuaParser(null)
        return XMakeLuaParserAdaptor(parser)
    }

    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRING

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY

    override fun getFileNodeType(): IFileElementType = FILE

    override fun createFile(viewProvider: FileViewProvider): PsiFile = XMakeLuaFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement {
        val elType = node.elementType

        when {
            elType == XMakeLanguageIElementTypes.SCRIPT_SCOPE -> return ScriptScope(node)
            elType == XMakeLanguageIElementTypes.GLOBAL_SCOPE -> return GlobalScope(node)
            elType is XMakeLanguageIElementTypes.DomainScopeType ->
                return if (elType.domainType == DomainScope.DomainType.NAMESPACE) NamespaceScope(node)
                else DomainScope(node, elType.domainType)
            elType is XMakeLanguageIElementTypes.NamespaceScopeType -> return NamespaceScope(node)
            elType is TokenIElementType && elType.antlrTokenType == LuaLexer.NAME -> return XMakeLuaIdentifier(elType, node.text)
            elType is TokenIElementType -> return ANTLRPsiNode(node)
            elType !is RuleIElementType -> return ANTLRPsiNode(node)
        }

        return when (elType.ruleIndex) {
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

    companion object {
        val logger = logger<XMakeLuaParserDefinition>()
    }
}

val FILE: IFileElementType = IFileElementType(XMakeLuaLanguage.INSTANCE)

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
