package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.LuaPsiVisibleLeaves
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaChunk
import io.xmake.lang.syntax.psi.lua.LuaFunctionName
import io.xmake.lang.syntax.psi.lua.LuaStatement

/**
 * Extracts public API names from local xmake module files.
 * PSI is the only semantic source; malformed or unparseable text produces no exports.
 */
object LocalModuleApiExtractor {

    private val SIMPLE_FUNCTION_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /**
     * Extracts top-level public (non-local, non-private) function names from Lua source text.
     */
    @Suppress("unused")
    fun extractTopLevelPublicFunctionNames(text: String): List<String> =
        parseTextAsPsi(text, project = null)
            ?.let(::extractTopLevelPublicFunctionNamesFromPsi)
            .orEmpty()

    /**
     * Project-aware overload used when import resolution starts from source text.
     */
    @Suppress("unused")
    fun extractTopLevelPublicFunctionNames(project: Project, text: String): List<String> =
        parseTextAsPsi(text, project)
            ?.let(::extractTopLevelPublicFunctionNamesFromPsi)
            .orEmpty()

    /**
     * Extracts top-level public (non-local, non-private) function names from a parsed Lua PSI file.
     */
    fun extractTopLevelPublicFunctionNames(file: PsiFile): List<String> =
        extractTopLevelPublicFunctionNamesFromPsi(file).orEmpty()

    fun extractTopLevelPublicFunctionDeclarations(project: Project, text: String): List<ModuleExportDeclaration> =
        parseTextAsPsi(text, project)
            ?.let(::extractTopLevelPublicFunctionDeclarationsFromPsiOrNull)
            ?.map(::toDetachedDeclaration)
            .orEmpty()

    fun extractTopLevelPublicFunctionDeclarations(file: PsiFile): List<ModuleExportDeclaration> =
        extractTopLevelPublicFunctionDeclarationsFromPsiOrNull(file).orEmpty()

    fun hasUnknownTopLevelPublicFunctionExports(project: Project, text: String): Boolean =
        parseTextAsPsi(text, project)
            ?.let(::hasUnknownTopLevelPublicFunctionExportsFromPsi)
            ?: false

    fun hasUnknownTopLevelPublicFunctionExports(file: PsiFile): Boolean =
        hasUnknownTopLevelPublicFunctionExportsFromPsi(file)

    /**
     * Checks if a function name is public (not starting with underscore).
     */
    private fun isPublicModuleFunctionName(name: String, project: Project): Boolean =
        SIMPLE_FUNCTION_NAME.matches(name) &&
            !name.startsWith("_") &&
            name !in scriptBuiltinApiNames(project)

    private fun scriptBuiltinApiNames(project: Project): Set<String> =
        ApiService.getInstance(project).getApis().scriptBuiltinApis.toSet()

    private data class FunctionDeclaration(
        val name: String,
        val path: List<String>,
        val isTopLevelScope: Boolean,
        val isLocalFunction: Boolean,
        val declarationElement: PsiElement?
    )

    private data class ParsedFunctionName(
        val path: List<String>
    )

    private fun extractTopLevelPublicFunctionNamesFromPsi(file: PsiFile): List<String>? =
        extractPublicFunctionNamesFromPsi(file)

    private fun extractTopLevelPublicFunctionDeclarationsFromPsiOrNull(file: PsiFile): List<ModuleExportDeclaration>? =
        collectFunctionDeclarations(file)?.let { declarations ->
            extractPublicFunctionDeclarations(declarations)
        }

    private fun extractPublicFunctionNamesFromPsi(file: PsiFile): List<String>? {
        val declarations = collectFunctionDeclarations(file) ?: return null
        return declarations
            .asSequence()
            .filter { it.isTopLevelScope }
            .filterNot { it.isLocalFunction }
            .filter { it.path.size == 1 }
            .map { it.name }
            .filter { name -> isPublicModuleFunctionName(name, file.project) }
            .distinct()
            .toList()
    }

    private fun extractPublicFunctionDeclarations(
        declarations: List<FunctionDeclaration>
    ): List<ModuleExportDeclaration> =
        declarations
            .asSequence()
            .filter { it.isTopLevelScope }
            .filterNot { it.isLocalFunction }
            .filter { it.path.size == 1 }
            .filter { declaration -> isPublicModuleFunctionName(declaration.name, declarationsProject(declaration)) }
            .map { declaration ->
                declaration.declarationElement?.let { element ->
                    ModuleExportDeclaration.fromPsi(declaration.name, element)
                } ?: ModuleExportDeclaration.detached(declaration.name)
            }
            .distinctBy(ModuleExportDeclaration::name)
            .toList()

    private fun toDetachedDeclaration(declaration: ModuleExportDeclaration): ModuleExportDeclaration =
        ModuleExportDeclaration.detached(
            name = declaration.name,
            declarationOffset = declaration.declarationOffset,
            declarationText = declaration.declarationText
        )

    private fun declarationsProject(declaration: FunctionDeclaration): Project =
        declaration.declarationElement?.project ?: ProjectManager.getInstance().defaultProject

    private fun collectFunctionDeclarations(root: PsiElement): List<FunctionDeclaration>? {
        val statements = PsiTreeUtil.findChildrenOfType(root, LuaStatement::class.java).toList()
        val hasLuaStructure = PsiTreeUtil.findChildOfType(root, LuaChunk::class.java) != null ||
                statements.isNotEmpty()
        if (!hasLuaStructure) {
            return null
        }
        return statements.mapNotNull(::toFunctionDeclaration)
    }

    private fun toFunctionDeclaration(statement: LuaStatement): FunctionDeclaration? {
        val declarationScope = statement.parent
        val isTopLevelScope = declarationScope is LuaChunk ||
                (declarationScope is LuaBlock && declarationScope.parent is LuaChunk)
        val isLocalFunction = isLocalFunctionDeclaration(statement)
        val parsedName = when {
            isFunctionDeclarationStatement(statement) -> parseFunctionName(statement)
            isTopLevelScope && !isLocalFunction -> parseFunctionAssignmentName(statement)
            else -> null
        } ?: return null
        return FunctionDeclaration(
            name = parsedName.path.last(),
            path = parsedName.path,
            isTopLevelScope = isTopLevelScope,
            isLocalFunction = isLocalFunction,
            declarationElement = resolveDeclarationElement(statement, parsedName.path.last())
        )
    }

    private fun isFunctionDeclarationStatement(statement: LuaStatement): Boolean {
        val first = LuaPsiVisibleLeaves.firstWithin(statement) ?: return false
        if (first.text == "function") {
            return true
        }
        return first.text == "local" && LuaPsiVisibleLeaves.nextWithin(first, statement)?.text == "function"
    }

    private fun hasUnknownTopLevelPublicFunctionExportsFromPsi(file: PsiFile): Boolean {
        val declarations = collectFunctionDeclarations(file) ?: return false
        val knownFunctionAssignmentStatements = declarations
            .mapNotNull { it.declarationElement }
            .mapNotNull { PsiTreeUtil.getParentOfType(it, LuaStatement::class.java) }
            .toSet()
        val statements = PsiTreeUtil.findChildrenOfType(file, LuaStatement::class.java)
        return statements.any { statement ->
            statement !in knownFunctionAssignmentStatements &&
                isUnknownTopLevelPublicAssignment(statement, file.project)
        }
    }

    private fun isUnknownTopLevelPublicAssignment(statement: LuaStatement, project: Project): Boolean {
        val declarationScope = statement.parent
        val isTopLevelScope = declarationScope is LuaChunk ||
                (declarationScope is LuaBlock && declarationScope.parent is LuaChunk)
        if (!isTopLevelScope || isLocalStatement(statement) || isFunctionDeclarationStatement(statement)) {
            return false
        }
        val tokens = statementTokens(statement)
        val assignmentIndex = tokens.indexOf("=")
        if (assignmentIndex != 1 || tokens.getOrNull(assignmentIndex + 1) == "function") {
            return false
        }
        val name = tokens.firstOrNull()?.takeIf(::isIdentifierToken) ?: return false
        return isPublicModuleFunctionName(name, project)
    }

    private fun resolveDeclarationElement(
        statement: LuaStatement,
        declaredName: String
    ): PsiElement? {
        val functionBody =
            PsiTreeUtil.findChildOfType(statement, io.xmake.lang.syntax.psi.lua.LuaFunctionBody::class.java)
        val declarationEndOffset = functionBody?.textRange?.startOffset ?: statement.textRange.endOffset

        val functionName = PsiTreeUtil.findChildOfType(statement, LuaFunctionName::class.java)
        if (functionName != null) {
            return PsiTreeUtil.findChildrenOfType(functionName, XMakeLuaIdentifier::class.java)
                .filter { it.text == declaredName }
                .maxByOrNull { it.textOffset }
        }

        val identifiers = PsiTreeUtil.findChildrenOfType(statement, XMakeLuaIdentifier::class.java)
            .asSequence()
            .filter { it.text == declaredName }
            .filter { it.textOffset < declarationEndOffset }
            .sortedBy { it.textOffset }
            .toList()
        return identifiers.lastOrNull()
    }

    private fun parseFunctionName(statement: LuaStatement): ParsedFunctionName? {
        val tokens = statementTokens(statement)
        val functionIndex = tokens.indexOf("function")
        if (functionIndex < 0) return null

        val path = mutableListOf<String>()
        var index = functionIndex + 1
        var expectIdentifier = true

        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "(") break
            if (expectIdentifier) {
                if (!isIdentifierToken(token)) return null
                path += token
                expectIdentifier = false
            } else {
                if (token != "." && token != ":") return null
                expectIdentifier = true
            }
            index++
        }

        if (path.isEmpty() || expectIdentifier) return null
        if (tokens.getOrNull(index) != "(") return null
        return ParsedFunctionName(path = path)
    }

    private fun isLocalFunctionDeclaration(statement: LuaStatement): Boolean {
        val first = LuaPsiVisibleLeaves.firstWithin(statement) ?: return false
        if (first.text != "local") return false
        return LuaPsiVisibleLeaves.nextWithin(first, statement)?.text == "function"
    }

    private fun isLocalStatement(statement: LuaStatement): Boolean =
        LuaPsiVisibleLeaves.firstWithin(statement)?.text == "local"

    private fun statementTokens(statement: LuaStatement): List<String> = buildList {
        var leaf = LuaPsiVisibleLeaves.firstWithin(statement)
        while (leaf != null) {
            add(leaf.text)
            leaf = LuaPsiVisibleLeaves.nextWithin(leaf, statement)
        }
    }

    private fun isIdentifierToken(token: String): Boolean = SIMPLE_FUNCTION_NAME.matches(token)

    private fun parseFunctionAssignmentName(statement: LuaStatement): ParsedFunctionName? {
        val tokens = statementTokens(statement)
        if (tokens.size < 4 || tokens.getOrNull(1) != "=" || tokens.getOrNull(2) != "function") {
            return null
        }
        val name = tokens.first()
        if (!isIdentifierToken(name)) {
            return null
        }
        if (tokens.getOrNull(3) != "(") {
            return null
        }
        return ParsedFunctionName(path = listOf(name))
    }

    private fun parseTextAsPsi(text: String, project: Project? = null): PsiFile? =
        runCatching {
            val resolvedProject = project
                ?: ProjectManager.getInstance().openProjects.firstOrNull()
                ?: ProjectManager.getInstance().defaultProject
            PsiFileFactory.getInstance(resolvedProject).createFileFromText(
                "module.lua",
                XMakeLuaLanguage,
                text
            )
        }.getOrNull()

}
