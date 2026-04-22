package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns
import io.xmake.lang.syntax.XMakeLuaLanguage

class XMakeCompletionContributor : CompletionContributor(), DumbAware {

    init {
        val basePattern = PlatformPatterns.psiElement()
            .withLanguage(XMakeLuaLanguage.INSTANCE)

        extend(CompletionType.BASIC, basePattern, XMakeCompletionProvider())
    }
}

