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
    language: Language, parser: Parser, psiBuilder: PsiBuilder
) : ANTLRParseTreeToPSIConverter(language, parser, psiBuilder), ParseTreeListener {
    private lateinit var globalScopeMarker: PsiBuilder.Marker
    private val descriptionScopeMarkers: Deque<Pair<PsiBuilder.Marker, String?>> = ArrayDeque()
    private val scriptScopeMarkers: Deque<PsiBuilder.Marker> = ArrayDeque()

    fun insideEnterFunctiondef(ctx: FunctiondefContext) {

    }

    fun insideExitFunctiondef(ctx: FunctiondefContext) {

    }

    private fun closeOpenDescriptionBlock() {
        if (descriptionScopeMarkers.isNotEmpty()) {
            descriptionScopeMarkers.pop().let { (marker, typeName) ->
                marker.done(XMakeLanguageIElementTypes.DescriptionScopeType(typeName))
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
                marker.done(XMakeLanguageIElementTypes.DescriptionScopeType(typeName))
            }
        }
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
            } else {
                context = context.parent
            }
        }
        return null
    }

    fun insideEnterBlock(ctx: BlockContext) {
        if (ctx.parent is FuncbodyContext &&
            ctx.findParentOfContext<FunctioncallContext>()?.NAME(0)?.text !in DomainType.types
        ) {
            val marker = builder.mark()
            scriptScopeMarkers.push(marker)
        }
        if (ctx.parent is ChunkContext){
            globalScopeMarker = builder.mark()
        }
    }

    fun insideExitBlock(ctx: BlockContext) {
        if (ctx.parent is FuncbodyContext &&
            ctx.findParentOfContext<FunctioncallContext>()?.NAME(0)?.text !in DomainType.types
        ) {
            if (scriptScopeMarkers.isNotEmpty()) {
                scriptScopeMarkers.pop().done(XMakeLanguageIElementTypes.ScriptScopeType())
            }
        }
        if (ctx.parent is ChunkContext){
            while (descriptionScopeMarkers.isNotEmpty()) {
                closeLastOpenDescriptionBlock()
            }
            globalScopeMarker.done(XMakeLanguageIElementTypes.DescriptionScopeType(null))
        }
    }

    fun outsideEnterStat(ctx: StatContext) {
        if (ctx.parent is BlockContext && ctx.parent.parent is ChunkContext) {
            getFunctionCall(ctx)?.let { functionCallContext ->
                getFunctionName(functionCallContext)?.let { functionName ->
                    if (functionName in DomainType.types) {
                        closeLastOpenDescriptionBlock()
                        val marker = builder.mark()
                        descriptionScopeMarkers.push(Pair(marker, functionName))
                    }
                }
            }
        }
    }

    fun outsideExitStat(ctx: StatContext) {
        if (ctx.parent is BlockContext && ctx.parent.parent is ChunkContext) {
            getFunctionCall(ctx)?.let { functionCallContext ->
                getFunctionName(functionCallContext)?.let { functionName ->
                    if (isDescriptionScopeEndFunction(functionName) ||
                        isSelfClosingDescription(functionCallContext, functionName)
                    ) {
                        closeOpenDescriptionBlock()
                    }
                }
            }
        }
    }

    private fun getFunctionCall(ctx: StatContext): FunctioncallContext? {
        if (ctx.functioncall() != null) {
            return ctx.functioncall()
        }
        return null
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
            is FunctiondefContext -> insideEnterFunctiondef(ctx)
        }
    }

    override fun exitEveryRule(ctx: ParserRuleContext?) {
        when (ctx) {
            is BlockContext -> insideExitBlock(ctx)
            is FunctiondefContext -> insideExitFunctiondef(ctx)
        }
        super.exitEveryRule(ctx)
        when (ctx) {
            is StatContext -> outsideExitStat(ctx)
        }
    }
}