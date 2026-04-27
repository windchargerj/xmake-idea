package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

class XMakeCompletionProvider : CompletionProvider<CompletionParameters>() {

    private data class ImportPathState(
        val parentPath: String,
        val segmentPrefix: String
    )

    override fun addCompletions(
        parameters: CompletionParameters,
        context: com.intellij.util.ProcessingContext,
        result: CompletionResultSet
    ) {
        ProgressManager.checkCanceled()

        val project = parameters.editor.project ?: return
        val position = parameters.position
        val analysisPosition = parameters.originalPosition ?: position

        if (CompletionSyntaxContext.isInsideComment(parameters)) {
            return
        }

        val completionContext = CompletionContextDetector.detect(parameters)
        if (CompletionSyntaxContext.isInsideStringLiteral(analysisPosition) &&
            completionContext !is CompletionContextDetector.CompletionContext.ImportPath
        ) {
            return
        }

        when (completionContext) {
            is CompletionContextDetector.CompletionContext.MemberAccess -> {
                addMemberCompletions(project, parameters, completionContext, result)
                return
            }

            is CompletionContextDetector.CompletionContext.ImportPath -> {
                processImportCompletion(project, position, completionContext.importPrefix, result)
                return
            }

            CompletionContextDetector.CompletionContext.Unknown -> return
            CompletionContextDetector.CompletionContext.Identifier -> Unit
        }

        if (CompletionSyntaxContext.isInsideTableConstructor(analysisPosition) &&
            !CompletionSyntaxContext.shouldAllowIdentifierCompletionInTable(analysisPosition)
        ) {
            return
        }
        tryIdentifierCompletion(project, parameters, result)
    }

    private fun processImportCompletion(
        project: Project,
        position: PsiElement,
        rawPrefix: String,
        result: CompletionResultSet
    ): Boolean {
        try {
            val importPathState = parseImportPathState(rawPrefix)
            val childModules = importChildModules(project, importPathState.parentPath, position)
            if (childModules.isEmpty()) {
                return false
            }

            val matchedModules = childModules.filter { moduleName ->
                importPathState.segmentPrefix.isBlank() ||
                        moduleName.startsWith(importPathState.segmentPrefix, ignoreCase = true)
            }
            if (matchedModules.isEmpty()) {
                return false
            }

            val unfilteredResult = result.withPrefixMatcher("")
            matchedModules.forEach { moduleName ->
                LookupElementFactory.addModulePathCompletion(
                    moduleName,
                    unfilteredResult,
                    importPathState.segmentPrefix.length
                )
            }
            return true
        } catch (e: Exception) {
            if (e is ProcessCanceledException) {
                throw e
            }
            return false
        }
    }

    private fun parseImportPathState(rawPrefix: String): ImportPathState {
        val prefix = rawPrefix.trim()
        if (prefix.isBlank()) {
            return ImportPathState("", "")
        }

        if (prefix.endsWith('.')) {
            return ImportPathState(prefix.removeSuffix("."), "")
        }

        val lastDot = prefix.lastIndexOf('.')
        if (lastDot < 0) {
            return ImportPathState("", prefix)
        }

        return ImportPathState(
            parentPath = prefix.substring(0, lastDot),
            segmentPrefix = prefix.substring(lastDot + 1)
        )
    }

    private fun addMemberCompletions(
        project: Project,
        parameters: CompletionParameters,
        memberAccess: CompletionContextDetector.CompletionContext.MemberAccess,
        result: CompletionResultSet
    ) {
        val receiverPath = memberAccess.receiverPath
        val memberAccessKind = memberAccess.memberAccessKind
        if (receiverPath.isBlank()) {
            return
        }

        val api = XMakeApi.getInstance(project)
        val apiContext = CompletionScopeResolver.inferLookupView(parameters)
        val place = parameters.originalPosition ?: parameters.position
        val memberPrefix = resolveMemberPrefix(parameters).orEmpty()
        val memberResult = result.withPrefixMatcher(memberPrefix)

        when (memberAccessKind) {
            CompletionContextDetector.MemberAccessKind.MODULE ->
                LookupElementFactory.addNestedModuleCompletions(
                    api,
                    receiverPath,
                    apiContext,
                    parameters.originalFile as? XMakeLuaFile,
                    PsiTreeUtil.getParentOfType(place, XMakeLuaIdentifier::class.java) ?: place,
                    memberResult
                )

            CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD ->
                LookupElementFactory.addInstanceMethodCompletions(
                    api,
                    receiverPath,
                    memberResult,
                    instanceCompletionView(apiContext)
                )
        }
    }

    private fun resolveMemberPrefix(parameters: CompletionParameters): String? {
        // Heuristic boundary: use the live document to keep member prefixes correct during incomplete input.
        val access = LuaLexicalTextSupport.detectMemberAccess(
            parameters.editor.document.text,
            parameters.editor.caretModel.offset
        ) ?: return null
        val prefixStart = (access.receiverEndOffset + 1).coerceAtMost(parameters.editor.document.textLength)
        val prefixEnd = parameters.editor.caretModel.offset.coerceAtLeast(prefixStart)
        return parameters.editor.document.text.substring(prefixStart, prefixEnd)
    }

    private fun instanceCompletionView(context: ApiLookupView): ApiLookupView {
        if (context.domain is XMakeDomain.Script) {
            return context
        }
        return context.withDomain(XMakeDomain.Script)
    }

    private fun tryIdentifierCompletion(
        project: Project,
        parameters: CompletionParameters,
        result: CompletionResultSet
    ) {
        val api = XMakeApi.getInstance(project)
        val apiContext = CompletionScopeResolver.inferLookupView(parameters)
        val position = parameters.originalPosition ?: parameters.position
        val place = PsiTreeUtil.getParentOfType(position, XMakeLuaIdentifier::class.java) ?: position
        LookupElementFactory.addNormalCompletions(
            api,
            apiContext,
            parameters.originalFile as? XMakeLuaFile,
            place,
            includeSiblingStructuralEntries = shouldIncludeSiblingStructuralEntries(parameters, apiContext),
            result = result
        )
    }

    private fun shouldIncludeSiblingStructuralEntries(
        parameters: CompletionParameters,
        apiContext: ApiLookupView
    ): Boolean {
        if (apiContext.domain !is XMakeDomain.Configuration) {
            return false
        }
        val prefix = identifierPrefix(parameters)
        return prefix.isNotBlank() &&
                XMakeDescriptionDomainRules.structuralEntryKeywords.any { entry -> entry.startsWith(prefix) }
    }

    private fun identifierPrefix(parameters: CompletionParameters): String {
        val documentText = parameters.editor.document.text
        val offset = parameters.offset.coerceIn(0, documentText.length)
        var start = offset
        while (start > 0 && isIdentifierPart(documentText[start - 1])) {
            start--
        }
        return documentText.substring(start, offset).substringBefore("IntellijIdeaRulezzz")
    }

    private fun isIdentifierPart(char: Char): Boolean =
        char == '_' || char.isLetterOrDigit()

    private fun importChildModules(project: Project, prefix: String, position: PsiElement?): List<String> =
        try {
            XMakeApi.getInstance(project).importChildModules(prefix, position)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
}

