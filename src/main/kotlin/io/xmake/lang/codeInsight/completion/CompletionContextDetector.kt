package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException

object CompletionContextDetector {

    enum class MemberAccessKind {
        MODULE,
        INSTANCE_METHOD
    }

    sealed interface CompletionContext {
        data class MemberAccess(
            override val receiverPath: String,
            override val memberAccessKind: MemberAccessKind
        ) : CompletionContext

        data class ImportPath(
            override val importPrefix: String
        ) : CompletionContext

        data object Identifier : CompletionContext

        data object Unknown : CompletionContext

        val receiverPath: String? get() = null
        val memberAccessKind: MemberAccessKind? get() = null
        val importPrefix: String? get() = null
    }

    private val LOG = logger<CompletionContextDetector>()

    fun detect(parameters: CompletionParameters): CompletionContext {
        val position = parameters.originalPosition ?: parameters.position
        val project = parameters.editor.project ?: return CompletionContext.Unknown
        val originalFile = CompletionMemberAccessAnalyzer.resolveXMakeFile(project, parameters.originalFile)
        val lookupView = CompletionScopeResolver.inferLookupView(parameters)

        LOG.debug("Starting context analysis at offset ${parameters.offset}")

        return try {
            CompletionSyntaxContext.detectImportPrefix(parameters)?.let { importPrefix ->
                LOG.debug("Detected import path context via text: prefix=$importPrefix")
                return CompletionContext.ImportPath(importPrefix)
            }

            if (CompletionSyntaxContext.isInsideImportString(position)) {
                LOG.debug("Detected import path context via PSI")
                return CompletionContext.ImportPath("")
            }

            CompletionMemberAccessAnalyzer.detectContext(position, parameters.editor, originalFile, lookupView)?.let { memberAccess ->
                LOG.debug("Detected member access context: receiver=${memberAccess.receiverPath}, kind=${memberAccess.memberAccessKind}")
                return memberAccess
            }

            LOG.debug("Falling back to identifier context")
            CompletionContext.Identifier
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Context analysis failed", e)
            CompletionContext.Unknown
        }
    }

}
