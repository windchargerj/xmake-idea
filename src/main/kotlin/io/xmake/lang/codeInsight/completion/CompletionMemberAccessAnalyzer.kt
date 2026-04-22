package io.xmake.lang.codeInsight.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.analysis.model.VisibleSymbol
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.text.LuaLexicalTextSupport

internal object CompletionMemberAccessAnalyzer {

    private data class MemberAccessCandidate(
        val receiverPath: String,
        val kind: CompletionContextDetector.MemberAccessKind,
        val receiverStartOffset: Int? = null,
        val receiverEndOffset: Int? = null
    )

    private sealed interface AliasMemberAccessResolution {
        data class Matched(val result: CompletionContextDetector.CompletionContext.MemberAccess) :
            AliasMemberAccessResolution

        data object Incompatible : AliasMemberAccessResolution
        data object Unresolved : AliasMemberAccessResolution
    }

    fun detectContext(
        position: PsiElement,
        editor: Editor? = null,
        originalFile: XMakeLuaFile? = null,
        apiContext: ApiLookupView
    ): CompletionContextDetector.CompletionContext.MemberAccess? {
        val editorCandidate = editor?.let { detectEditorCandidate(it) }
        if (editorCandidate != null) {
            return resolveCandidate(editorCandidate, position, originalFile, apiContext)
        }

        val psiCandidate = detectPsiCandidate(position) ?: return null
        return resolveCandidate(psiCandidate, position, originalFile, apiContext)
    }

    fun isValidModule(
        moduleName: String,
        position: PsiElement,
        originalFile: XMakeLuaFile? = null,
        apiContext: ApiLookupView
    ): Boolean {
        return try {
            val api = XMakeApi.getInstance(position.project)
            val file = originalFile ?: resolveXMakeFile(position.project, position.containingFile)
            // Member access should only accept modules that are visible from the current
            // domain, such as builtins or imported aliases. Canonical extension-module
            // paths like `core.base` are handled later once semantic analysis resolves an
            // alias/import to that canonical path.
            api.isVisibleModulePath(moduleName, apiContext, file, position)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun resolveXMakeFile(project: Project, file: PsiFile?): XMakeLuaFile? {
        return when (file) {
            is XMakeLuaFile -> file
            null -> null
            else -> file.virtualFile?.let { PsiManager.getInstance(project).findFile(it) as? XMakeLuaFile }
        }
    }

    private fun resolveAliasedMemberAccess(
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        receiverPath: String,
        expectedKind: CompletionContextDetector.MemberAccessKind,
        receiverStartOffset: Int?,
        receiverEndOffset: Int?,
        apiContext: ApiLookupView
    ): AliasMemberAccessResolution {
        if (receiverStartOffset != null && receiverEndOffset != null) {
            when (val receiverBinding = resolveReceiverBindingTarget(
                position,
                originalFile,
                receiverStartOffset,
                receiverEndOffset,
                apiContext
            )) {
                is VisibleSymbol.ImportedModule -> {
                    if (expectedKind == CompletionContextDetector.MemberAccessKind.MODULE) {
                        return AliasMemberAccessResolution.Matched(moduleMemberAccessResult(receiverPath))
                    }
                }

                is VisibleSymbol.Local,
                is VisibleSymbol.InheritedApi,
                is VisibleSymbol.Synthetic,
                is VisibleSymbol.BuiltinModule,
                is VisibleSymbol.BuiltinApi ->
                    receiverBinding.inferredType?.let { return matchReceiverType(expectedKind, it) }

                null -> Unit
            }

            resolveImportedModuleBinding(
                position = position,
                originalFile = originalFile,
                receiverPath = receiverPath,
                expectedKind = expectedKind,
                caretOffset = receiverEndOffset,
                apiContext = apiContext
            )?.let { return it }

            resolveReceiverExpressionType(
                position,
                originalFile,
                receiverStartOffset,
                receiverEndOffset,
                apiContext
            )?.let { inferredType ->
                return matchReceiverType(expectedKind, inferredType)
            }
        }

        if (receiverStartOffset == null || receiverEndOffset == null) {
            return resolveAliasedMemberAccess(position, receiverPath, expectedKind, position.textOffset, apiContext)
        }
        return resolveAnalysisPositions(position, originalFile, receiverStartOffset, receiverEndOffset)
            .map { candidate ->
                resolveAliasedMemberAccess(candidate, receiverPath, expectedKind, receiverEndOffset, apiContext)
            }
            .firstOrNull { it !is AliasMemberAccessResolution.Unresolved }
            ?: AliasMemberAccessResolution.Unresolved
    }

    private fun resolveAliasedMemberAccess(
        position: PsiElement,
        receiverPath: String,
        expectedKind: CompletionContextDetector.MemberAccessKind,
        caretOffset: Int,
        apiContext: ApiLookupView
    ): AliasMemberAccessResolution {
        if (receiverPath.contains('.') || receiverPath.contains(':')) {
            return AliasMemberAccessResolution.Unresolved
        }

        resolveImportedModuleBinding(position, receiverPath, expectedKind, caretOffset, apiContext)?.let { return it }

        findReceiverIdentifier(position, receiverPath, caretOffset, apiContext)?.let { receiverIdentifier ->
            val inferredType = IdentifierAnalysis.inferType(receiverIdentifier, apiContext)
            return matchReceiverType(expectedKind, inferredType)
        }

        val inferredType =
            when (val binding = IdentifierAnalysis.visibleSymbol(position, receiverPath, caretOffset, apiContext)) {
                is VisibleSymbol.ImportedModule -> {
                    // Imported-module bindings must still pass the place-sensitive import lookup
                    // checks below. Otherwise copied-PSI completion can leak imports from a
                    // different script region in the same file.
                    return AliasMemberAccessResolution.Unresolved
                }

                is VisibleSymbol.Local,
                is VisibleSymbol.InheritedApi,
                is VisibleSymbol.Synthetic,
                is VisibleSymbol.BuiltinModule,
                is VisibleSymbol.BuiltinApi -> binding.inferredType

                null -> return AliasMemberAccessResolution.Unresolved
            }

        return matchReceiverType(expectedKind, inferredType)
    }

    private fun resolveReceiverBindingTarget(
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        receiverStartOffset: Int,
        receiverEndOffset: Int,
        apiContext: ApiLookupView
    ): VisibleSymbol? {
        return resolveAnalysisPositions(position, originalFile, receiverStartOffset, receiverEndOffset)
            .mapNotNull { candidate ->
                val candidateFile = candidate.containingFile ?: return@mapNotNull null
                val receiverIdentifier = identifiersInRange(candidateFile, receiverStartOffset, receiverEndOffset)
                    .firstOrNull() ?: return@mapNotNull null
                IdentifierAnalysis.visibleSymbol(candidate, receiverIdentifier.text, receiverEndOffset, apiContext)
            }
            .firstOrNull()
    }

    private fun resolveImportedModuleBinding(
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        receiverPath: String,
        expectedKind: CompletionContextDetector.MemberAccessKind,
        caretOffset: Int,
        apiContext: ApiLookupView
    ): AliasMemberAccessResolution? {
        if (!isSimpleModuleReceiver(receiverPath, expectedKind)) {
            return null
        }

        return resolveAnalysisPositions(
            position,
            originalFile,
            caretOffset,
            caretOffset
        ).firstNotNullOfOrNull { candidate ->
            resolveImportedModuleBinding(
                candidate,
                receiverPath,
                expectedKind,
                caretOffset,
                apiContext
            )
        }
    }

    private fun resolveImportedModuleBinding(
        position: PsiElement,
        receiverPath: String,
        expectedKind: CompletionContextDetector.MemberAccessKind,
        caretOffset: Int,
        @Suppress("UNUSED_PARAMETER") apiContext: ApiLookupView
    ): AliasMemberAccessResolution? {
        if (!isSimpleModuleReceiver(receiverPath, expectedKind)) {
            return null
        }

        if (findReceiverIdentifier(position, receiverPath, caretOffset, apiContext) != null) {
            return null
        }

        return if (IdentifierAnalysis.visibleSymbol(
                position,
                receiverPath,
                caretOffset,
                apiContext
            ) is VisibleSymbol.ImportedModule
        ) {
            AliasMemberAccessResolution.Matched(moduleMemberAccessResult(receiverPath))
        } else {
            null
        }
    }

    private fun matchReceiverType(
        expectedKind: CompletionContextDetector.MemberAccessKind,
        inferredType: XMakeType?
    ): AliasMemberAccessResolution {
        return when (expectedKind) {
            CompletionContextDetector.MemberAccessKind.MODULE ->
                if (inferredType is XMakeType.Module) {
                    AliasMemberAccessResolution.Matched(moduleMemberAccessResult(inferredType.path))
                } else {
                    AliasMemberAccessResolution.Incompatible
                }

            CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD ->
                if (inferredType is XMakeType.Instance) {
                    AliasMemberAccessResolution.Matched(instanceMethodAccessResult(inferredType.typeName))
                } else {
                    AliasMemberAccessResolution.Incompatible
                }
        }
    }

    private fun findReceiverIdentifier(
        position: PsiElement,
        receiverName: String,
        caretOffset: Int,
        apiContext: ApiLookupView
    ): XMakeLuaIdentifier? {
        ProgressManager.checkCanceled()
        when (val binding = IdentifierAnalysis.visibleSymbol(
            position,
            receiverName,
            caretOffset,
            apiContext
        )) {
            is VisibleSymbol.Local -> return binding.declaration
            is VisibleSymbol.ImportedModule,
            is VisibleSymbol.InheritedApi,
            is VisibleSymbol.Synthetic,
            is VisibleSymbol.BuiltinModule,
            is VisibleSymbol.BuiltinApi -> return null

            null -> Unit
        }

        return IdentifierAnalysis.visibleDeclarations(position, caretOffset)
            .firstOrNull { declaration ->
                ProgressManager.checkCanceled()
                declaration.name == receiverName
            }
    }

    private fun resolveReceiverExpressionType(
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        receiverStartOffset: Int,
        receiverEndOffset: Int,
        apiContext: ApiLookupView
    ): XMakeType? {
        return resolveAnalysisPositions(position, originalFile, receiverStartOffset, receiverEndOffset)
            .mapNotNull { candidate ->
                val candidateFile = candidate.containingFile ?: return@mapNotNull null
                identifiersInRange(candidateFile, receiverStartOffset, receiverEndOffset)
                    .firstNotNullOfOrNull { IdentifierAnalysis.inferType(it, apiContext) }
            }
            .firstOrNull()
    }

    private fun identifiersInRange(
        file: PsiElement,
        receiverStartOffset: Int,
        receiverEndOffset: Int
    ): Sequence<XMakeLuaIdentifier> =
        PsiTreeUtil.findChildrenOfType(file, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textRange.startOffset >= receiverStartOffset && it.textRange.endOffset <= receiverEndOffset }
            .sortedByDescending { it.textOffset }

    private fun isSimpleModuleReceiver(
        receiverPath: String,
        expectedKind: CompletionContextDetector.MemberAccessKind
    ): Boolean =
        expectedKind == CompletionContextDetector.MemberAccessKind.MODULE &&
                !receiverPath.contains('.') &&
                !receiverPath.contains(':')

    private fun resolveAnalysisPositions(
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        receiverStartOffset: Int,
        receiverEndOffset: Int
    ): Sequence<PsiElement> = sequence {
        val file = originalFile ?: return@sequence
        if (position.containingFile == file) {
            preferredAnalysisOffsets(file.textLength, receiverStartOffset, receiverEndOffset)
                .mapNotNull(file::findElementAt)
                .filter { it != position }
                .forEach { yield(it) }
            yield(position)
            return@sequence
        }

        val textLength = file.textLength
        if (textLength > 0) {
            preferredAnalysisOffsets(textLength, receiverStartOffset, receiverEndOffset)
                .asSequence()
                .mapNotNull(file::findElementAt)
                .filter { it != position }
                .forEach { yield(it) }
        }

        yield(position)
    }

    private fun preferredAnalysisOffsets(
        textLength: Int,
        receiverStartOffset: Int,
        receiverEndOffset: Int
    ): List<Int> {
        if (textLength <= 0) {
            return emptyList()
        }
        return listOf(
            (receiverEndOffset - 1).coerceIn(0, textLength - 1),
            receiverStartOffset.coerceIn(0, textLength - 1),
            receiverEndOffset.coerceIn(0, textLength - 1)
        ).distinct()
    }

    private fun moduleMemberAccessResult(receiverPath: String) = CompletionContextDetector.CompletionContext.MemberAccess(
        receiverPath = receiverPath,
        memberAccessKind = CompletionContextDetector.MemberAccessKind.MODULE
    )

    private fun instanceMethodAccessResult(receiverPath: String) =
        CompletionContextDetector.CompletionContext.MemberAccess(
            receiverPath = receiverPath,
            memberAccessKind = CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD
        )

    private fun detectEditorCandidate(editor: Editor): MemberAccessCandidate? {
        // Heuristic boundary: the editor document sees member access before PSI is fully reparsed.
        val access =
            LuaLexicalTextSupport.detectMemberAccess(editor.document.text, editor.caretModel.offset) ?: return null
        val kind = when (access.separator) {
            '.' -> CompletionContextDetector.MemberAccessKind.MODULE
            ':' -> CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD
            else -> return null
        }
        return MemberAccessCandidate(
            receiverPath = access.receiverPath,
            kind = kind,
            receiverStartOffset = access.receiverStartOffset,
            receiverEndOffset = access.receiverEndOffset
        )
    }

    private fun detectPsiCandidate(position: PsiElement): MemberAccessCandidate? {
        val prevSibling = position.prevSibling
        if (prevSibling != null && (prevSibling.text == "." || prevSibling.text == ":")) {
            val receiverIdentifier = prevSibling.prevSibling as? XMakeLuaIdentifier
            if (receiverIdentifier != null) {
                return MemberAccessCandidate(
                    receiverPath = receiverIdentifier.text,
                    kind = if (prevSibling.text == ".") {
                        CompletionContextDetector.MemberAccessKind.MODULE
                    } else {
                        CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD
                    }
                )
            }
        }

        val previousLeaf = PsiTreeUtil.prevVisibleLeaf(position)
        if (previousLeaf != null && (previousLeaf.text == "." || previousLeaf.text == ":")) {
            buildPsiCandidateFromSeparator(previousLeaf)?.let { return it }
        }

        val functionCall = PsiTreeUtil.getParentOfType(position, LuaFunctionCall::class.java) ?: return null
        val children = functionCall.children
        val separatorIndex = children.indexOfFirst { it.text == "." || it.text == ":" }
        if (separatorIndex <= 0) {
            return null
        }
        val receiverIdentifier = children.getOrNull(separatorIndex - 1) as? XMakeLuaIdentifier ?: return null
        return MemberAccessCandidate(
            receiverPath = receiverIdentifier.text,
            kind = if (children[separatorIndex].text == ".") {
                CompletionContextDetector.MemberAccessKind.MODULE
            } else {
                CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD
            }
        )
    }

    private fun buildPsiCandidateFromSeparator(separatorLeaf: PsiElement): MemberAccessCandidate? {
        val functionCall = PsiTreeUtil.getParentOfType(separatorLeaf, LuaFunctionCall::class.java) ?: return null
        val separatorStart = separatorLeaf.textRange.startOffset
        val identifiers = PsiTreeUtil.findChildrenOfType(functionCall, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.textRange.endOffset <= separatorStart }
            .sortedBy { it.textOffset }
            .toList()
        val receiverIdentifier = identifiers.lastOrNull() ?: return null
        val receiverStartOffset =
            identifiers.firstOrNull()?.textRange?.startOffset ?: receiverIdentifier.textRange.startOffset
        val fileText = functionCall.containingFile?.text ?: return null
        val receiverPath = fileText.substring(receiverStartOffset, separatorStart).trim()
        if (receiverPath.isBlank()) {
            return null
        }
        return MemberAccessCandidate(
            receiverPath = receiverPath,
            kind = if (separatorLeaf.text == ".") {
                CompletionContextDetector.MemberAccessKind.MODULE
            } else {
                CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD
            },
            receiverStartOffset = receiverStartOffset,
            receiverEndOffset = separatorStart
        )
    }

    private fun resolveCandidate(
        candidate: MemberAccessCandidate,
        position: PsiElement,
        originalFile: XMakeLuaFile?,
        apiContext: ApiLookupView
    ): CompletionContextDetector.CompletionContext.MemberAccess? {
        return when (
            val aliasResolution = resolveAliasedMemberAccess(
                position = position,
                originalFile = originalFile,
                receiverPath = candidate.receiverPath,
                expectedKind = candidate.kind,
                receiverStartOffset = candidate.receiverStartOffset,
                receiverEndOffset = candidate.receiverEndOffset,
                apiContext = apiContext
            )
        ) {
            is AliasMemberAccessResolution.Matched -> aliasResolution.result
            AliasMemberAccessResolution.Incompatible -> null
            AliasMemberAccessResolution.Unresolved -> when (candidate.kind) {
                CompletionContextDetector.MemberAccessKind.MODULE ->
                    candidate.receiverPath
                        .takeIf { it.isNotEmpty() && isValidModule(it, position, originalFile, apiContext) }
                        ?.let(::moduleMemberAccessResult)

                CompletionContextDetector.MemberAccessKind.INSTANCE_METHOD -> null
            }
        }
    }
}

