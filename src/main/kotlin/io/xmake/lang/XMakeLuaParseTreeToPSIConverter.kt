package io.xmake.lang

import com.intellij.lang.Language
import com.intellij.lang.PsiBuilder
import com.intellij.lang.WhitespacesBinders
import io.xmake.lang.antlr.LuaParser.*
import io.xmake.lang.psi.xmake.DomainScope.DomainType
import org.antlr.intellij.adaptor.parser.ANTLRParseTreeToPSIConverter
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.Tree
import java.util.*

class XMakeLuaParseTreeToPSIConverter(
    language: Language,
    parser: Parser,
    psiBuilder: PsiBuilder
) : ANTLRParseTreeToPSIConverter(language, parser, psiBuilder), ParseTreeListener {

    private lateinit var globalScopeMarker: PsiBuilder.Marker
    private val descriptionScopeMarkers: Deque<DescriptionScopeMarker> = ArrayDeque()
    private val scriptScopeMarkers: Deque<PsiBuilder.Marker> = ArrayDeque()

    private data class DescriptionScopeMarker(
        val marker: PsiBuilder.Marker,
        val typeName: String?
    )

    private fun closeOpenDescriptionBlock() {
        if (descriptionScopeMarkers.isNotEmpty()) {
            descriptionScopeMarkers.pop().let { (marker, typeName) ->
                marker.done(resolveDescriptionScopeType(typeName))
            }
        }
    }

    private fun closeLastOpenDescriptionBlock() {
        if (descriptionScopeMarkers.isNotEmpty()) {
            descriptionScopeMarkers.pop().let { (marker, typeName) ->
                marker.setCustomEdgeTokenBinders(
                    WhitespacesBinders.DEFAULT_LEFT_BINDER,
                    WhitespacesBinders.GREEDY_RIGHT_BINDER
                )
                marker.done(resolveDescriptionScopeType(typeName))
            }
        }
    }

    private fun resolveDescriptionScopeType(typeName: String?) = when {
        typeName == null -> XMakeLanguageIElementTypes.GLOBAL_SCOPE
        typeName in DomainType.types -> XMakeLanguageIElementTypes.domainScope(typeName)!!
        else -> XMakeLanguageIElementTypes.GLOBAL_SCOPE
    }

    private fun isSelfClosingDescription(ctx: FunctioncallContext, functionName: String): Boolean {
        if (functionName !in DomainType.types) return false
        val expList = ctx.args()?.firstOrNull()?.explist() ?: return false
        return expList.exp().any { it.functiondef() != null }
    }

    private fun isDescriptionScopeEndFunction(functionName: String): Boolean =
        functionName.endsWith("_end") && functionName.removeSuffix("_end") in DomainType.types

    private inline fun <reified T : ParserRuleContext> Tree.findParentOfContext(): T? {
        var context = this
        while (context.parent != null) {
            if (context.parent is T) {
                return context.parent as T
            }
            context = context.parent
        }
        return null
    }

    fun insideEnterBlock(ctx: BlockContext) {
        if (ctx.parent is FuncbodyContext) {
            val marker = builder.mark()
            scriptScopeMarkers.push(marker)
        }
        if (ctx.parent is ChunkContext) {
            globalScopeMarker = builder.mark()
        }
    }

    fun insideExitBlock(ctx: BlockContext) {
        if (ctx.parent is FuncbodyContext) {
            if (scriptScopeMarkers.isNotEmpty()) {
                scriptScopeMarkers.pop().done(XMakeLanguageIElementTypes.SCRIPT_SCOPE)
            }
        }
        if (ctx.parent is ChunkContext) {
            while (descriptionScopeMarkers.isNotEmpty()) {
                closeLastOpenDescriptionBlock()
            }
            globalScopeMarker.done(XMakeLanguageIElementTypes.GLOBAL_SCOPE)
        }
    }

    fun outsideEnterStat(ctx: StatContext) {
        if (ctx.parent is BlockContext && ctx.parent.parent is ChunkContext) {
            getFunctionCall(ctx)?.let { functionCallContext ->
                getFunctionName(functionCallContext)?.let { functionName ->
                    if (functionName in DomainType.types) {
                        closeLastOpenDescriptionBlock()
                        val marker = builder.mark()
                        descriptionScopeMarkers.push(DescriptionScopeMarker(marker, functionName))
                    }
                }
            }
        }
    }

    fun outsideExitStat(ctx: StatContext) {
        if (ctx.parent is BlockContext && ctx.parent.parent is ChunkContext) {
            val functionName = getFunctionName(ctx.functioncall())
            if (functionName != null && functionName in DomainType.types) {
                if (isDescriptionScopeEndFunction(functionName) ||
                    isSelfClosingWithFunction(ctx) ||
                    isSelfClosingWithDo(ctx)
                ) {
                    closeOpenDescriptionBlock()
                }
            }
        }
    }

    private fun isSelfClosingWithFunction(ctx: StatContext): Boolean {
        val functionCall = ctx.functioncall() ?: return false
        val args = functionCall.args() ?: return false
        val expList = args.firstOrNull()?.explist() ?: return false
        return expList.exp().any { it.functiondef() != null }
    }

    private fun isSelfClosingWithDo(ctx: StatContext): Boolean {
        return false
    }

    private fun getFunctionCall(ctx: StatContext): FunctioncallContext? {
        return ctx.functioncall()
    }

    private fun getFunctionName(ctx: FunctioncallContext?): String? {
        return ctx?.NAME()?.singleOrNull()?.text
    }

    override fun enterEveryRule(ctx: ParserRuleContext?) {
        when (ctx) {
            is StatContext -> outsideEnterStat(ctx)
        }
        super.enterEveryRule(ctx)
        when (ctx) {
            is BlockContext -> insideEnterBlock(ctx)
        }
    }

    override fun exitEveryRule(ctx: ParserRuleContext?) {
        when (ctx) {
            is BlockContext -> insideExitBlock(ctx)
        }
        super.exitEveryRule(ctx)
        when (ctx) {
            is StatContext -> outsideExitStat(ctx)
        }
    }
}
