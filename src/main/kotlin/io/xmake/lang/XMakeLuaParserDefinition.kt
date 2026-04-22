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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.xmake.lang.antlr.LuaLexer
import io.xmake.lang.antlr.LuaParser
import io.xmake.lang.psi.XMakeLuaFile
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.lexer.TokenIElementType
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
        return when (node.elementType) {
            is TokenIElementType ->
                ANTLRPsiNode(node)
            !is RuleIElementType ->
                ANTLRPsiNode(node)
            else -> ANTLRPsiNode(node)
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
