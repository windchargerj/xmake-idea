package io.xmake.lang.declarations.import

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.ApiException
import io.xmake.lang.scope.query.XMakeScopeQuery
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaExpression
import io.xmake.lang.syntax.psi.lua.LuaExpressionList
import io.xmake.lang.syntax.psi.lua.LuaAttributeNameList
import io.xmake.lang.syntax.psi.lua.LuaFieldList
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaStatement
import io.xmake.lang.syntax.psi.lua.LuaString
import io.xmake.lang.syntax.psi.lua.LuaTableConstructor
import io.xmake.lang.syntax.psi.lua.LuaVariableList

object ImportCallParser {

    private val IMPORT_FUNCTION_NAMES = setOf("import", "inherit")

    fun parse(call: LuaFunctionCall): Result<ImportSpec> {
        return parseDeclaration(call).map(ModuleImportDeclaration::spec)
    }

    fun resolveRootDir(call: LuaFunctionCall): String? {
        return ReadAction.compute<String?, RuntimeException> {
            parseOptions(call).rootDir
        }
    }

    fun resolveNoLocal(call: LuaFunctionCall): Boolean {
        return ReadAction.compute<Boolean, RuntimeException> {
            parseOptions(call).noLocal
        }
    }

    internal fun parseDeclaration(call: LuaFunctionCall): Result<ModuleImportDeclaration> {
        return ReadAction.compute<Result<ModuleImportDeclaration>, RuntimeException> {
            parseDeclarationInternal(call)
        }
    }

    internal fun parseAllFrom(element: PsiElement): Sequence<ModuleImportDeclaration> {
        return ReadAction.compute<List<ModuleImportDeclaration>, RuntimeException> {
            parseAllFromInternal(element).toList()
        }.asSequence()
    }

    fun isImportCall(call: LuaFunctionCall): Boolean {
        return ReadAction.compute<Boolean, RuntimeException> {
            call.calleeName?.let(IMPORT_FUNCTION_NAMES::contains) == true
        }
    }

    fun resolveImportScanRoot(element: PsiElement): PsiElement =
        XMakeScopeQuery.scriptSearchRoot(element)

    private fun parseAllFromInternal(element: PsiElement): Sequence<ModuleImportDeclaration> {
        val importScanRoot = resolveImportScanRoot(element)
        return PsiTreeUtil.findChildrenOfType(importScanRoot, LuaFunctionCall::class.java)
            .asSequence()
            .mapNotNull { parseDeclaration(it).getOrNull() }
    }

    private fun parseDeclarationInternal(call: LuaFunctionCall): Result<ModuleImportDeclaration> {
        return try {
            val functionName = call.calleeName
                ?: return Result.failure(
                    ApiException.ImportParseException("Missing function name")
                )

            if (functionName !in IMPORT_FUNCTION_NAMES) {
                return Result.failure(
                    ApiException.ImportParseException("Expected import() or inherit() call")
                )
            }

            val args = call.arguments
            if (args.isEmpty()) {
                return Result.failure(
                    ApiException.ImportParseException("Missing module path argument")
                )
            }

            val modulePath = extractModulePath(args[0])
                .getOrElse { return Result.failure(it) }
            val options = if (args.size > 1) parseOptions(args[1]) else parseOptions(call)
            val variableAlias = extractVariableAlias(call)

            Result.success(
                ModuleImportDeclaration(
                    spec = options.toImportSpec(
                        modulePath = modulePath,
                        forceInherit = functionName == "inherit"
                    ),
                    variableAlias = variableAlias,
                    declarationPointer = SmartPointerManager.createPointer(call as PsiElement)
                )
            )
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(parseError(e.message))
        }
    }

    private fun parseError(message: String?): ApiException.ImportParseException =
        ApiException.ImportParseException("Parse error: $message")

    private fun extractModulePath(arg: PsiElement): Result<String> {
        val text = extractStaticStringLiteralText(arg)
            ?: return Result.failure(
                ApiException.ImportParseException("Module path must be a string literal")
            )

        if (text.length < 2) {
            return Result.failure(ApiException.ImportParseException("Invalid string literal"))
        }

        val unquoted = when {
            text.startsWith("'") && text.endsWith("'") -> text.substring(1, text.length - 1)
            text.startsWith("\"") && text.endsWith("\"") -> text.substring(1, text.length - 1)
            else -> text
        }

        if (unquoted.isBlank()) {
            return Result.failure(ApiException.ImportParseException("Module path cannot be empty"))
        }

        return Result.success(unquoted)
    }

    private fun extractStaticStringLiteralText(arg: PsiElement): String? {
        return when (arg) {
            is LuaString -> arg.text
            is LuaExpression -> {
                val stringNode = PsiTreeUtil.getChildOfType(arg, LuaString::class.java) ?: return null
                stringNode.text.takeIf { arg.text.trim() == it }
            }

            else -> null
        }
    }

    private fun parseOptions(arg: PsiElement): ImportCallOptions {
        val table = PsiTreeUtil.findChildOfType(arg, LuaTableConstructor::class.java) ?: return ImportCallOptions()
        val fieldList = PsiTreeUtil.findChildOfType(table, LuaFieldList::class.java) ?: return ImportCallOptions()

        var alias: String? = null
        var anonymous = false
        var inherit = false
        var tryImport = false
        var alwaysBuild = false
        var noLocal = false
        var rootDir: String? = null

        for (child in fieldList.children) {
            val key = PsiTreeUtil.findChildOfType(child, XMakeLuaIdentifier::class.java) ?: continue
            val value = PsiTreeUtil.findChildOfType(child, LuaExpression::class.java) ?: continue

            when (key.text) {
                "alias" -> alias = extractStringValue(value)
                "anonymous" -> anonymous = extractBooleanValue(value)
                "inherit" -> inherit = extractBooleanValue(value)
                "try" -> tryImport = extractBooleanValue(value)
                "always_build" -> alwaysBuild = extractBooleanValue(value)
                "nolocal" -> noLocal = extractBooleanValue(value)
                "rootdir" -> rootDir = extractStringValue(value)
            }
        }

        return ImportCallOptions(
            alias = alias,
            anonymous = anonymous,
            inherit = inherit,
            tryImport = tryImport,
            alwaysBuild = alwaysBuild,
            noLocal = noLocal,
            rootDir = rootDir
        )
    }

    private fun extractStringValue(expr: LuaExpression): String? {
        val stringNode = PsiTreeUtil.findChildOfType(expr, LuaString::class.java) ?: return null
        val text = stringNode.text
        if (text.length < 2) return null
        return text.drop(1).dropLast(1).takeIf { it.isNotBlank() }
    }

    private fun extractBooleanValue(expr: LuaExpression): Boolean {
        return expr.text.trim() == "true"
    }

    private fun extractVariableAlias(call: LuaFunctionCall): String? {
        val statement = PsiTreeUtil.getParentOfType(call, LuaStatement::class.java) ?: return null
        val variableList = PsiTreeUtil.findChildOfType(statement, LuaVariableList::class.java)
        val expressionList = PsiTreeUtil.findChildOfType(statement, LuaExpressionList::class.java)

        if (variableList != null && expressionList != null) {
            extractVariableAliasFromLists(call, variableList, expressionList)?.let { return it }
        }

        return extractVariableAliasByOffsets(call, statement)
    }

    private fun extractVariableAliasFromLists(
        call: LuaFunctionCall,
        variableList: LuaVariableList,
        expressionList: LuaExpressionList
    ): String? {
        val variables = variableAliases(
            statement = PsiTreeUtil.getParentOfType(variableList, LuaStatement::class.java),
            variableList = variableList
        )
        if (variables.isEmpty()) {
            return null
        }

        val expressions = expressionList.getExpressions()
        val expressionIndex = expressions.indexOfFirst { expression ->
            expression.textRange.contains(call.textRange)
        }
        if (expressionIndex < 0) {
            return null
        }

        return variables.getOrNull(expressionIndex)?.text
    }

    private fun extractVariableAliasByOffsets(call: LuaFunctionCall, statement: LuaStatement): String? {
        val eqLeaf = LuaPsiVisibleLeaves.findWithin(statement, "=") ?: return null
        val eqOffset = eqLeaf.textRange.startOffset
        if (eqOffset < 0) {
            return null
        }

        val lhsIdentifiers = variableAliases(statement = statement)
            .filter { it.textOffset < eqOffset }
        if (lhsIdentifiers.isEmpty()) {
            return null
        }

        val rhsExpressions = PsiTreeUtil.findChildrenOfType(statement, LuaExpression::class.java)
            .asSequence()
            .filter { expression -> expression.textRange.startOffset > eqOffset }
            .sortedBy { it.textOffset }
            .toList()
        val expressionIndex = rhsExpressions.indexOfFirst { expression ->
            expression.textRange.contains(call.textRange)
        }
        if (expressionIndex < 0) {
            return null
        }

        return lhsIdentifiers.getOrNull(expressionIndex)?.text
    }

    private fun variableAliases(
        statement: LuaStatement?,
        variableList: LuaVariableList? = null
    ): List<XMakeLuaIdentifier> {
        val attributeNameList = statement
            ?.let { PsiTreeUtil.getChildOfType(it, LuaAttributeNameList::class.java) }
        if (attributeNameList != null) {
            return PsiTreeUtil.findChildrenOfType(attributeNameList, XMakeLuaIdentifier::class.java)
                .filter { it.parent == attributeNameList }
                .sortedBy { it.textOffset }
        }

        val root = variableList ?: statement ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(root, XMakeLuaIdentifier::class.java)
            .asSequence()
            .sortedBy { it.textOffset }
            .toList()
    }
}

private data class ImportCallOptions(
    val alias: String? = null,
    val anonymous: Boolean = false,
    val inherit: Boolean = false,
    val tryImport: Boolean = false,
    val alwaysBuild: Boolean = false,
    val noLocal: Boolean = false,
    val rootDir: String? = null
) {
    fun toImportSpec(modulePath: String, forceInherit: Boolean): ImportSpec =
        ImportSpec(
            modulePath = modulePath,
            alias = alias,
            anonymous = anonymous,
            inherit = inherit || forceInherit,
            tryImport = tryImport,
            alwaysBuild = alwaysBuild,
            noLocal = noLocal,
            rootDir = rootDir
        )
}
