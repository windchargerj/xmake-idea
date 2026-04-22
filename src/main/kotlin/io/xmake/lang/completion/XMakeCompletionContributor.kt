package io.xmake.lang.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.findParentOfType
import com.intellij.util.ProcessingContext
import io.xmake.lang.XMakeLuaLanguage
import io.xmake.lang.psi.XMakeLuaIdentifier
import io.xmake.lang.psi.lua.LuaTableConstructor
import io.xmake.lang.psi.xmake.DomainScope
import io.xmake.lang.psi.xmake.GlobalScope
import io.xmake.lang.psi.xmake.Scope
import io.xmake.lang.psi.xmake.ScriptScope
import io.xmake.utils.info.toCallee
import io.xmake.utils.info.xmakeInfo

class XMakeCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(XMakeLuaIdentifier::class.java)
                .withLanguage(XMakeLuaLanguage.INSTANCE)
                .andNot(PlatformPatterns.psiElement().inside(LuaTableConstructor::class.java)),
            object : CompletionProvider<CompletionParameters?>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val project = parameters.editor.project ?: return
                    val apis = project.xmakeInfo.apis
                    val functionsMap = when (val parentScope = parameters.position.findParentOfType<Scope>()) {
                        is GlobalScope -> {
                            val globalFunctionsMap =
                                (apis.descriptionBuiltinApis + DomainScope.DomainType.types)
                                    .groupBy { "" }
                                    .mapKeys { "Global" }

                            val targetFunctionsMap =
                                apis.descriptionScopeApis.toCallee()
                                    .filterKeys { it == DomainScope.DomainType.TARGET.text }
                                    .mapKeys { "Target" }

                            globalFunctionsMap + targetFunctionsMap
                        }

                        is DomainScope -> {
                            apis.descriptionScopeApis.toCallee().filterKeys {
                                it == parentScope.type.text
                            }
                        }
                        is ScriptScope -> {
                            mapOf()
                        }
                        else -> mapOf()
                    }

                    functionsMap
                        .flatMap { (type, functions) -> functions.map { type to it } }
                        .forEach { (type, function) ->
                        result.addElement(
                            LookupElementBuilder.create(function)
                                .withIcon(AllIcons.Nodes.Function)
                                .withTypeText(type)
                                .withInsertHandler(ParenthesesInsertHandler.getInstance(true))
                        )
                    }
                }
            }
        )
    }
}