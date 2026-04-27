package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.xmakeApi
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.lua.LuaExpression
import io.xmake.lang.syntax.psi.lua.LuaFieldList
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaString
import io.xmake.lang.syntax.psi.lua.LuaTableConstructor

/**
 * Collects import declarations and related issues from PSI.
 *
 * [ImportLookup] is the public entry point for consumers that need a
 * position-aware [ImportLookupView].
 */
object ImportDeclarationCollector {
    private val addImportsSupportedDomains = setOf(
        XMakeConfigurationDomainType.TARGET,
        XMakeConfigurationDomainType.OPTION,
        XMakeConfigurationDomainType.RULE,
        XMakeConfigurationDomainType.PACKAGE
    )

    internal data class CollectionResult(
        val declarations: List<ImportDeclaration>,
        val issues: List<ImportIssue> = emptyList()
    )

    internal fun filterReachableDeclarations(
        declarations: List<ImportDeclaration>,
        place: PsiElement?,
        lookupOffset: Int
    ): List<ImportDeclaration> {
        if (place == null || place is XMakeLuaFile) {
            return declarations
        }
        return declarations.filter { declaration ->
            val declarationElement = declaration.declarationElement ?: return@filter false
            if (declaration is ModuleImportDeclaration && declarationElement.textRange.endOffset > lookupOffset) {
                return@filter false
            }
            declarationElement.isVisibleFrom(place)
        }
    }

    internal fun collectModuleDeclarations(importScanRoot: PsiElement): CollectionResult {
        return try {
            val declarations = mutableListOf<ImportDeclaration>()
            val issues = mutableListOf<ImportIssue>()
            PsiTreeUtil.findChildrenOfType(importScanRoot, LuaFunctionCall::class.java)
                .asSequence()
                .filter(ImportCallParser::isImportCall)
                .forEach { call ->
                    ImportCallParser.parseDeclaration(call)
                        .onSuccess(declarations::add)
                        .onFailure { error ->
                            issues += ImportIssue.fromElement(
                                kind = ImportIssue.Kind.DECLARATION_PARSE_FAILED,
                                message = error.message ?: "Failed to parse import declaration.",
                                element = call
                            )
                        }
                }
            CollectionResult(declarations = declarations, issues = issues)
        } catch (e: RuntimeException) {
            CollectionResult(
                declarations = emptyList(),
                issues = listOf(
                    ImportIssue.fromElement(
                        kind = ImportIssue.Kind.DECLARATION_COLLECTION_FAILED,
                        message = "Failed to collect import declarations: ${e.message ?: e.javaClass.simpleName}",
                        element = importScanRoot
                    )
                )
            )
        }
    }

    internal fun collectAddImportDeclarations(
        project: Project,
        anchor: PsiElement
    ): CollectionResult {
        return try {
            val declarations = mutableListOf<ImportDeclaration>()
            val issues = mutableListOf<ImportIssue>()
            PsiTreeUtil.findChildrenOfType(anchor.containingFile, LuaFunctionCall::class.java)
                .asSequence()
                .filter { call -> call.calleeName == "add_imports" }
                .filter { call -> isSupportedAddImportsCall(call, project) }
                .forEach { call ->
                    call.arguments
                        .flatMap(::extractAddImportModulePaths)
                        .forEach { result -> collectAddImportModulePathResult(result, call, declarations, issues) }
                }
            CollectionResult(declarations = declarations, issues = issues)
        } catch (e: RuntimeException) {
            CollectionResult(
                declarations = emptyList(),
                issues = listOf(
                    ImportIssue.fromElement(
                        kind = ImportIssue.Kind.ADD_IMPORT_COLLECTION_FAILED,
                        message = "Failed to collect add_imports declarations: ${e.message ?: e.javaClass.simpleName}",
                        element = anchor.containingFile
                    )
                )
            )
        }
    }

    private fun collectAddImportModulePathResult(
        result: AddImportModulePathResult,
        call: LuaFunctionCall,
        declarations: MutableList<ImportDeclaration>,
        issues: MutableList<ImportIssue>
    ) {
        when (result) {
            is AddImportModulePathResult.Valid -> {
                declarations += AddImportDeclaration(
                    modulePath = result.modulePath,
                    declarationPointer = SmartPointerManager.createPointer(call as PsiElement)
                )
            }

            is AddImportModulePathResult.Invalid -> {
                issues += ImportIssue.fromElement(
                    kind = ImportIssue.Kind.ADD_IMPORT_PARSE_FAILED,
                    message = "add_imports() expects non-blank string module paths.",
                    element = result.element
                )
            }
        }
    }

    private fun isSupportedAddImportsCall(
        call: LuaFunctionCall,
        project: Project
    ): Boolean {
        val state = XMakeScopeQuery.stateAt(call)
        val domainType = (state.domain as? XMakeDomain.Configuration)?.type ?: return false
        val apiFullName = "${domainType.toKeyword()}.add_imports"
        return domainType in addImportsSupportedDomains &&
            project.xmakeApi.findApiByFullName(apiFullName) != null
    }

    private fun extractAddImportModulePaths(arg: PsiElement): List<AddImportModulePathResult> {
        val table = PsiTreeUtil.findChildOfType(arg, LuaTableConstructor::class.java)
        if (table != null) {
            return extractAddImportModulePathsFromArray(table)
        }

        return listOf(
            extractAddImportModulePath(arg)
                ?: AddImportModulePathResult.Invalid(arg)
        )
    }

    private fun extractAddImportModulePathsFromArray(table: LuaTableConstructor): List<AddImportModulePathResult> {
        val fieldList = PsiTreeUtil.findChildOfType(table, LuaFieldList::class.java)
            ?: return listOf(AddImportModulePathResult.Invalid(table))

        val entries = fieldList.children.mapNotNull { field ->
            PsiTreeUtil.findChildOfType(field, LuaExpression::class.java)?.let { expression ->
                if ("=" in field.text) {
                    AddImportModulePathResult.Invalid(field)
                } else {
                    extractAddImportModulePath(expression) ?: AddImportModulePathResult.Invalid(field)
                }
            }
        }
        return entries.ifEmpty { listOf(AddImportModulePathResult.Invalid(table)) }
    }

    private fun extractAddImportModulePath(arg: PsiElement): AddImportModulePathResult.Valid? {
        val stringNode = PsiTreeUtil.findChildOfType(arg, LuaString::class.java) ?: return null
        val text = stringNode.text
        if (text.length < 2) {
            return null
        }
        return text.unquoteLuaString()
            .takeIf(String::isNotBlank)
            ?.let(AddImportModulePathResult::Valid)
    }

    private fun PsiElement.isVisibleFrom(place: PsiElement): Boolean {
        val declarationState = XMakeScopeQuery.stateAt(this)
        val placeState = XMakeScopeQuery.stateAt(place)
        return declarationState.root == placeState.root &&
            declarationState.region.contains(place.textOffset)
    }

    private fun String.unquoteLuaString(): String =
        when {
            startsWith("'") && endsWith("'") -> removeSurrounding("'")
            startsWith("\"") && endsWith("\"") -> removeSurrounding("\"")
            else -> this
        }

    private sealed interface AddImportModulePathResult {
        data class Valid(val modulePath: String) : AddImportModulePathResult
        data class Invalid(val element: PsiElement) : AddImportModulePathResult
    }
}
