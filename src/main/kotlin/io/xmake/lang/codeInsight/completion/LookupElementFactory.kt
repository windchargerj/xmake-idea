package io.xmake.lang.codeInsight.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.analysis.service.IdentifierAnalysis
import io.xmake.lang.declarations.ApiLookupContext
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.import.ImportedModuleView
import io.xmake.lang.declarations.model.ApiModel
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

object LookupElementFactory {

    private val LUA_KEYWORDS = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
        "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
        "then", "true", "until", "while"
    )

    private data class ModuleCompletionData(
        val functions: List<ApiModel>,
        val childModules: List<String>,
        val isValidModule: Boolean
    )

    private data class InheritedCompletionData(
        val functions: List<ApiModel>
    )

    private fun resolveModuleCompletionData(
        api: XMakeApi,
        file: XMakeLuaFile?,
        place: PsiElement?,
        modulePath: String,
        context: ApiLookupView
    ): ModuleCompletionData = ModuleCompletionData(
        functions = api.visibleModuleFunctions(modulePath, context, file, place),
        childModules = api.visibleChildModulesForReceiver(modulePath, context, file, place),
        isValidModule = api.isVisibleModulePath(modulePath, context, file, place)
    )

    private fun ModuleCompletionData.hasCandidates(): Boolean =
        isValidModule || functions.isNotEmpty() || childModules.isNotEmpty()

    private fun ModuleCompletionData.isEmpty(): Boolean = !hasCandidates()

    private fun resolveCanonicalExtensionModuleData(
        api: XMakeApi,
        modulePath: String,
        context: ApiLookupView
    ): ModuleCompletionData = ModuleCompletionData(
        functions = api.indexedModuleFunctions(modulePath, context),
        childModules = api.extensionChildModules(modulePath),
        isValidModule = true
    )

    private fun resolveInheritedCompletionData(
        api: XMakeApi,
        file: XMakeLuaFile?,
        place: PsiElement?
    ): InheritedCompletionData {
        val sourceFile = file ?: (place?.containingFile as? XMakeLuaFile)
            ?: return InheritedCompletionData(emptyList())
        val inheritedPlace = when {
            place == null || place.containingFile == sourceFile -> place
            sourceFile.textLength <= 0 -> sourceFile
            else -> sourceFile.findElementAt(place.textOffset.coerceIn(0, sourceFile.textLength - 1)) ?: sourceFile
        }
        val inheritedModules = api.imports.listInheritedModules(
            file = sourceFile,
            place = inheritedPlace
        )

        val functions = inheritedModules
            .flatMap { it.apis }
            .distinctBy { it.fullName }

        return InheritedCompletionData(functions)
    }

    fun addNestedModuleCompletions(
        api: XMakeApi,
        modulePath: String,
        apiContext: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement? = null,
        result: CompletionResultSet
    ) {
        var data = resolveModuleCompletionData(api, file, place, modulePath, apiContext)
        val canonicalModuleContext = when {
            apiContext.domain is XMakeDomain.Script -> apiContext
            else -> place
                ?.let { ApiLookupContext.at(it) }
                ?.takeIf { it.domain is XMakeDomain.Script }
        }

        if (data.isEmpty() &&
            canonicalModuleContext != null &&
            api.isExtensionModulePath(modulePath)
        ) {
            // The receiver may already be a canonical extension-module path resolved from
            // an alias such as import(...) or add_imports(). Reuse the current
            // place context instead of forcing script domain from the UI layer.
            data = resolveCanonicalExtensionModuleData(api, modulePath, canonicalModuleContext)
        }

        if (!data.hasCandidates()) {
            return
        }

        data.childModules.forEach { moduleName ->
            result.addElement(
                LookupElementBuilder.create(moduleName)
                    .withIcon(AllIcons.Nodes.Package)
                    .withTypeText(modulePath)
            )
        }

        data.functions.forEach { apiModel ->
            result.addElement(
                LookupElementBuilder.create(apiModel.name)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTypeText(modulePath)
                    .withInsertHandler(ParenthesesInsertHandler.getInstance(true))
            )
        }
    }

    fun addInstanceMethodCompletions(
        api: XMakeApi,
        instanceType: String,
        result: CompletionResultSet,
        context: ApiLookupView
    ) {
        api.instanceApis(instanceType, context)
            .forEach { apiModel ->
                result.addElement(
                    LookupElementBuilder.create(apiModel.name)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTypeText(instanceType)
                        .withInsertHandler(ParenthesesInsertHandler.getInstance(true))
                )
            }
    }

    fun addNormalCompletions(
        api: XMakeApi,
        apiContext: ApiLookupView,
        file: XMakeLuaFile?,
        place: PsiElement? = null,
        includeSiblingStructuralEntries: Boolean = false,
        result: CompletionResultSet
    ) {
        val seenNames = linkedSetOf<String>()
        addVisibleLocalCompletions(file, place, result, seenNames)

        api.visibleBuiltinModulePaths(apiContext)
            .sorted()
            .forEach { moduleName ->
                if (!seenNames.add(moduleName)) {
                    return@forEach
                }
                result.addElement(
                    LookupElementBuilder.create(moduleName)
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("module")
                )
            }

        if (apiContext.domain is XMakeDomain.Script) {
            addImportedReceiverCompletions(api, file, place, result, seenNames)
        }

        api.availableTopLevelCallables(apiContext)
            .forEach { apiModel ->
                if (seenNames.add(apiModel.name)) {
                    addApiCompletion(apiModel, result)
                }
            }

        if (includeSiblingStructuralEntries && apiContext.domain is XMakeDomain.Configuration) {
            addSiblingStructuralEntryCompletions(api, result, seenNames)
        }

        val inherited = resolveInheritedCompletionData(api, file, place)
        inherited.functions.forEach { apiModel ->
            if (seenNames.add(apiModel.name)) {
                addApiCompletion(apiModel, result)
            }
        }

        addKeywordCompletions(result, seenNames)
    }

    private fun addSiblingStructuralEntryCompletions(
        api: XMakeApi,
        result: CompletionResultSet,
        seenNames: MutableSet<String>
    ) {
        XMakeDescriptionDomainRules.structuralEntryKeywords.forEach { entryName ->
            if (!seenNames.add(entryName)) {
                return@forEach
            }
            api.findApisByName(entryName)
                .firstOrNull()
                ?.let { addApiCompletion(it, result) }
        }
    }

    private fun addImportedReceiverCompletions(
        api: XMakeApi,
        file: XMakeLuaFile?,
        place: PsiElement?,
        result: CompletionResultSet,
        seenNames: MutableSet<String>
    ) {
        val sourceFile = file ?: (place?.containingFile as? XMakeLuaFile) ?: return
        val importedModules = api.imports.viewAt(sourceFile, place).importedModules
        importedModules
            .flatMap(ImportedModuleView::receiverNames)
            .sorted()
            .forEach { receiverName ->
                if (!seenNames.add(receiverName)) {
                    return@forEach
                }
                result.addElement(
                    LookupElementBuilder.create(receiverName)
                        .withIcon(AllIcons.Nodes.Package)
                        .withTypeText("imported module")
                )
            }
    }

    fun addApiCompletion(api: ApiModel, result: CompletionResultSet) {
        val builder = LookupElementBuilder.create(api.name)
            .withIcon(AllIcons.Nodes.Function)
            .withTypeText(api.displayText)

        result.addElement(
            builder.withInsertHandler(XMakeCompletionInsertHandlers.handlerFor(api))
        )
    }

    fun addModulePathCompletion(moduleName: String, result: CompletionResultSet, segmentPrefixLength: Int = 0) {
        result.addElement(
            LookupElementBuilder.create(moduleName)
                .withIcon(AllIcons.Nodes.Package)
                .withTypeText("module")
                .withInsertHandler { context, _ ->
                    val replaceStart = (context.startOffset - segmentPrefixLength).coerceAtLeast(0)
                    context.document.replaceString(replaceStart, context.tailOffset, moduleName)
                    context.editor.caretModel.moveToOffset(replaceStart + moduleName.length)
                }
        )
    }

    private fun addVisibleLocalCompletions(
        file: XMakeLuaFile?,
        place: PsiElement?,
        result: CompletionResultSet,
        seenNames: MutableSet<String>
    ) {
        val sourcePlace = normalizeCompletionPlace(file, place) ?: return
        val beforeOffset = resolveLocalCompletionOffset(file, place) ?: return

        IdentifierAnalysis.visibleDeclarations(sourcePlace, beforeOffset)
            .asSequence()
            .forEach { declaration ->
                val name = declaration.name ?: return@forEach
                if (!seenNames.add(name)) {
                    return@forEach
                }
                result.addElement(buildLocalCompletion(declaration))
            }
    }

    private fun normalizeCompletionPlace(file: XMakeLuaFile?, place: PsiElement?): PsiElement? {
        val sourcePlace = place ?: return null
        val targetFile = when {
            file == null || sourcePlace.containingFile == file -> sourcePlace.containingFile
            file.textLength <= 0 -> file
            else -> file
        }
        if (targetFile == null || targetFile.textLength <= 0) {
            return targetFile
        }

        val offset = when {
            targetFile === sourcePlace.containingFile -> sourcePlace.textOffset.coerceIn(0, targetFile.textLength - 1)
            else -> sourcePlace.textOffset.coerceIn(0, targetFile.textLength - 1)
        }
        val mappedLeaf = targetFile.findElementAt(offset) ?: return targetFile
        val anchorLeaf = if (mappedLeaf.text.isBlank()) {
            PsiTreeUtil.prevVisibleLeaf(mappedLeaf) ?: PsiTreeUtil.nextVisibleLeaf(mappedLeaf) ?: mappedLeaf
        } else {
            mappedLeaf
        }
        return PsiTreeUtil.getParentOfType(anchorLeaf, XMakeLuaIdentifier::class.java, false) ?: anchorLeaf
    }

    private fun resolveLocalCompletionOffset(file: XMakeLuaFile?, place: PsiElement?): Int? {
        val sourcePlace = place ?: return null
        return when {
            file == null || sourcePlace.containingFile == file -> sourcePlace.textOffset
            file.textLength <= 0 -> 0
            else -> sourcePlace.textOffset.coerceIn(0, file.textLength)
        }
    }

    private fun buildLocalCompletion(declaration: XMakeLuaIdentifier): LookupElementBuilder {
        return when {
            PsiPredicates.isBindableFunctionDeclarationName(declaration) ->
                LookupElementBuilder.create(declaration.name ?: declaration.text)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTypeText("local function")
                    .withInsertHandler(ParenthesesInsertHandler.getInstance(true))

            PsiPredicates.isParameter(declaration) ->
                LookupElementBuilder.create(declaration.name ?: declaration.text)
                    .withIcon(AllIcons.Nodes.Parameter)
                    .withTypeText("parameter")

            else ->
                LookupElementBuilder.create(declaration.name ?: declaration.text)
                    .withIcon(AllIcons.Nodes.Variable)
                    .withTypeText("local")
        }
    }

    private fun addKeywordCompletions(
        result: CompletionResultSet,
        seenNames: MutableSet<String>
    ) {
        LUA_KEYWORDS.forEach { keyword ->
            if (!seenNames.add(keyword)) {
                return@forEach
            }
            result.addElement(
                LookupElementBuilder.create(keyword)
                    .withBoldness(true)
                    .withTypeText("keyword")
            )
        }
    }
}

