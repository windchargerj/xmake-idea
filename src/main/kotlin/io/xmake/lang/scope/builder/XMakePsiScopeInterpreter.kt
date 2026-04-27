package io.xmake.lang.scope.builder

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.scope.issue.ScopeIssue
import io.xmake.lang.scope.model.XMakeDomain
import io.xmake.lang.scope.model.XMakeDescriptionDomainRules
import io.xmake.lang.scope.model.XMakeRegion
import io.xmake.lang.scope.model.XMakeRoot
import io.xmake.lang.scope.model.ScriptSearchRoot
import io.xmake.lang.scope.model.XMakeFileScopeModel
import io.xmake.lang.scope.model.XMakeConfigurationDomainType
import io.xmake.lang.scope.model.XMakeConfigurationEntryCall
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.lang.syntax.psi.lua.LuaBlock
import io.xmake.lang.syntax.psi.lua.LuaFunctionBody
import io.xmake.lang.syntax.psi.lua.LuaFunctionCall
import io.xmake.lang.syntax.psi.lua.LuaStatement
import java.util.ArrayDeque

internal object XMakePsiScopeInterpreter {

    fun interpret(file: PsiFile): XMakeFileScopeModel =
        Interpreter(file).build()

    private class Interpreter(private val file: PsiFile) {
        private val regions = mutableListOf(
            XMakeRegion(
                domain = XMakeDomain.Description,
                root = XMakeRoot.Global,
                range = TextRange(0, file.textLength),
                source = XMakeRegion.Source.PSI
            )
        )
        private val issues = mutableListOf<ScopeIssue>()
        private val scriptSearchRoots = mutableListOf<ScriptSearchRoot>()
        private val openFrames = ArrayDeque<OpenDescriptionFrame>()

        fun build(): XMakeFileScopeModel {
            val rootBlock = PsiTreeUtil.findChildOfType(file, LuaBlock::class.java)
                ?: return XMakeFileScopeModel(regions = regions, issues = issues)

            walkBlock(rootBlock, Phase.DESCRIPTION)
            closeUnclosedFrames()

            return XMakeFileScopeModel(
                regions = regions.sortedWith(compareBy<XMakeRegion>({ it.startOffset }, { it.endOffset - it.startOffset })),
                scriptSearchRoots = scriptSearchRoots.sortedBy { it.range.startOffset },
                issues = issues.sortedBy { it.range.startOffset }
            )
        }

        private fun walkBlock(block: LuaBlock, phase: Phase) {
            walkChildren(block, phase)
        }

        private fun walkStatement(statement: LuaStatement, phase: Phase) {
            val call = statement.children.filterIsInstance<LuaFunctionCall>().firstOrNull()
            val identifier = call?.calleeIdentifier
            val functionName = identifier?.name
            val isDescriptionApi = phase == Phase.DESCRIPTION

            val openedDescriptionEntry = if (isDescriptionApi) {
                openDescriptionFrame(statement, call, identifier, functionName)
            } else {
                null
            }

            walkNestedScopes(statement, phase)

            if (!isDescriptionApi || functionName == null) {
                return
            }

            val shouldSelfCloseConfiguration =
                openedDescriptionEntry != null &&
                    openedDescriptionEntry.opensConfigurationFrame &&
                    XMakeDescriptionDomainRules.isConfigurationDomainEntry(functionName) &&
                    openedDescriptionEntry.isSelfClosingConfiguration
            val shouldSelfCloseNamespace =
                XMakeDescriptionDomainRules.isNamespaceEntry(functionName) &&
                    isSelfClosingNamespaceEntry(call)

            when {
                isDescriptionStructureEndFunction(functionName) ->
                    closeMatchingDescriptionFrame(statement, identifier, functionName)

                shouldSelfCloseConfiguration ->
                    closeTopDescriptionFrame(statement.textRange.endOffset)

                shouldSelfCloseNamespace ->
                    closeCurrentNamespace(statement.textRange.endOffset)
            }
        }

        private fun walkNestedScopes(element: PsiElement, phase: Phase) {
            walkChildren(element, phase)
        }

        private fun walkChildren(element: PsiElement, phase: Phase) {
            element.children.forEach { child ->
                when (child) {
                    is LuaStatement -> walkStatement(child, phase)
                    is LuaFunctionBody -> walkFunctionBody(child, phase)
                    is LuaBlock -> walkBlock(child, phase)
                    else -> walkChildren(child, phase)
                }
            }
        }

        private fun walkFunctionBody(functionBody: LuaFunctionBody, outerPhase: Phase) {
            val block = PsiTreeUtil.getChildOfType(functionBody, LuaBlock::class.java) ?: return
            if (outerPhase == Phase.DESCRIPTION && isConfigurationDomainFunctionBody(functionBody)) {
                walkBlock(block, Phase.DESCRIPTION)
                return
            }

            // Use the full function-body span instead of the parsed LuaBlock span so
            // Script domain still covers empty or incomplete hook bodies while editing.
            regions += XMakeRegion(
                domain = XMakeDomain.Script,
                root = currentRoot(),
                range = functionBody.textRange,
                source = XMakeRegion.Source.PSI
            )
            scriptSearchRoots += ScriptSearchRoot.from(functionBody.textRange, block)
            walkBlock(block, Phase.SCRIPT)
        }

        private fun openDescriptionFrame(
            statement: LuaStatement,
            call: LuaFunctionCall?,
            identifier: XMakeLuaIdentifier?,
            functionName: String?
        ): XMakeConfigurationEntryCall? {
            if (functionName == null || !isDescriptionStructureEntryFunction(functionName)) {
                return null
            }

            if (XMakeDescriptionDomainRules.isNamespaceEntry(functionName)) {
                val namespaceName = call?.firstStaticStringArgument?.takeIf { it.isNotBlank() }
                openFrames.addLast(
                    OpenDescriptionFrame.Namespace(
                        startOffset = statement.textRange.startOffset,
                        root = XMakeRoot.Namespace(
                            parent = currentRoot(),
                            name = namespaceName,
                            identity = namespaceName ?: dynamicNamespaceIdentity(statement.textRange.startOffset)
                        )
                    )
                )
                return null
            }

            val entryCall = XMakeConfigurationEntryCall.from(functionName, call) ?: return null
            entryCall.entryIssueMessage?.let { message ->
                issues += ScopeIssue(
                    kind = ScopeIssue.Kind.INVALID_SCOPE_ENTRY,
                    range = identifier?.textRange ?: statement.textRange,
                    message = message
                )
            }

            if (!entryCall.opensConfigurationFrame) {
                return entryCall
            }
            closeOpenConfiguration(statement.textRange.startOffset)
            openFrames.addLast(
                OpenDescriptionFrame.Configuration(
                    startOffset = statement.textRange.startOffset,
                    type = entryCall.type,
                    root = currentRoot(),
                    source = if (entryCall.usesRecoveryFrame) XMakeRegion.Source.RECOVERY else XMakeRegion.Source.PSI
                )
            )
            return entryCall
        }

        private fun closeMatchingDescriptionFrame(
            statement: LuaStatement,
            identifier: XMakeLuaIdentifier?,
            functionName: String
        ) {
            if (XMakeDescriptionDomainRules.isNamespaceEnd(functionName)) {
                closeMatchingNamespaceFrame(statement, identifier, functionName)
                return
            }

            val currentFrame = openFrames.lastOrNull()
            val matches = when {
                currentFrame is OpenDescriptionFrame.Configuration ->
                    XMakeDescriptionDomainRules.matchesConfigurationDomainEnd(currentFrame.type, functionName)

                else -> false
            }

            if (matches) {
                closeTopDescriptionFrame(statement.textRange.endOffset)
                return
            }

            val range = identifier?.textRange ?: statement.textRange
            issues += ScopeIssue(
                kind = ScopeIssue.Kind.UNMATCHED_SCOPE_END,
                range = range,
                message = "Unmatched '$functionName'; current domain is ${describeCurrentDomain()}."
            )
        }

        private fun closeMatchingNamespaceFrame(
            statement: LuaStatement,
            identifier: XMakeLuaIdentifier?,
            functionName: String
        ) {
            if (openFrames.none { it is OpenDescriptionFrame.Namespace }) {
                val range = identifier?.textRange ?: statement.textRange
                issues += ScopeIssue(
                    kind = ScopeIssue.Kind.UNMATCHED_SCOPE_END,
                    range = range,
                    message = "Unmatched '$functionName'; current domain is ${describeCurrentDomain()}."
                )
                return
            }

            closeOpenConfigurations(statement.textRange.startOffset)
            closeTopDescriptionFrame(statement.textRange.endOffset)
        }

        private fun closeOpenConfiguration(endOffset: Int) {
            if (openFrames.lastOrNull() is OpenDescriptionFrame.Configuration) {
                closeTopDescriptionFrame(endOffset)
            }
        }

        private fun closeOpenConfigurations(endOffset: Int) {
            while (openFrames.lastOrNull() is OpenDescriptionFrame.Configuration) {
                closeTopDescriptionFrame(endOffset)
            }
        }

        private fun closeCurrentNamespace(endOffset: Int) {
            closeOpenConfigurations(endOffset)
            if (openFrames.lastOrNull() is OpenDescriptionFrame.Namespace) {
                closeTopDescriptionFrame(endOffset)
            }
        }

        private fun closeTopDescriptionFrame(endOffset: Int) {
            if (openFrames.isEmpty()) {
                return
            }
            val frame = openFrames.removeLast()
            regions += frame.toRegion(endOffset)
        }

        private fun closeUnclosedFrames() {
            val eofOffset = file.textLength
            while (openFrames.isNotEmpty()) {
                val frame = openFrames.removeLast()
                regions += frame.toRegion(eofOffset)
                if (frame.requiresExplicitEnd) {
                    issues += ScopeIssue(
                        kind = ScopeIssue.Kind.UNCLOSED_SCOPE,
                        range = TextRange(eofOffset, eofOffset),
                        message = "Unclosed ${frame.describe()}."
                    )
                }
            }
        }

        private fun currentRoot(): XMakeRoot =
            openFrames.lastOrNull { it is OpenDescriptionFrame.Namespace }?.root ?: XMakeRoot.Global

        private fun describeCurrentDomain(): String {
            val currentFrame = openFrames.lastOrNull()
            return when (currentFrame) {
                is OpenDescriptionFrame.Configuration -> "configuration domain (${currentFrame.type.toKeyword()})"
                is OpenDescriptionFrame.Namespace -> "Description domain (namespace)"
                null -> "Description domain (global)"
            }
        }

        private fun isDescriptionStructureEntryFunction(functionName: String): Boolean =
            XMakeDescriptionDomainRules.isStructuralEntry(functionName)

        private fun isDescriptionStructureEndFunction(functionName: String): Boolean =
            XMakeDescriptionDomainRules.isStructuralEnd(functionName)

        private fun isSelfClosingNamespaceEntry(functionCall: LuaFunctionCall?): Boolean =
            functionCall?.argumentKind(1) == LuaFunctionCall.ArgumentKind.FUNCTION

        private fun dynamicNamespaceIdentity(startOffset: Int): String =
            "<dynamic>@$startOffset"

        private fun isConfigurationDomainFunctionBody(functionBody: LuaFunctionBody): Boolean {
            val functionCall = PsiTreeUtil.getParentOfType(functionBody, LuaFunctionCall::class.java) ?: return false
            val calledName = functionCall.calleeName ?: return false
            val entryCall = XMakeConfigurationEntryCall.from(calledName, functionCall) ?: return false
            if (!entryCall.opensConfigurationFrame) return false
            return functionCall.arguments.any { argument ->
                PsiTreeUtil.findChildOfType(argument, LuaFunctionBody::class.java) == functionBody
            }
        }
    }

    private enum class Phase {
        DESCRIPTION,
        SCRIPT
    }

    private sealed interface OpenDescriptionFrame {
        val startOffset: Int
        val root: XMakeRoot

        fun toRegion(endOffset: Int): XMakeRegion

        fun describe(): String

        val requiresExplicitEnd: Boolean

        data class Namespace(
            override val startOffset: Int,
            override val root: XMakeRoot
        ) : OpenDescriptionFrame {
            override fun toRegion(endOffset: Int): XMakeRegion =
                XMakeRegion(
                    domain = XMakeDomain.Description,
                    root = root,
                    range = TextRange(startOffset, endOffset.coerceAtLeast(startOffset)),
                    source = XMakeRegion.Source.PSI
                )

            override fun describe(): String = "Description domain (namespace)"

            override val requiresExplicitEnd: Boolean = true
        }

        data class Configuration(
            override val startOffset: Int,
            val type: XMakeConfigurationDomainType,
            override val root: XMakeRoot,
            val source: XMakeRegion.Source = XMakeRegion.Source.PSI
        ) : OpenDescriptionFrame {
            override fun toRegion(endOffset: Int): XMakeRegion =
                XMakeRegion(
                    domain = XMakeDomain.Configuration(type),
                    root = root,
                    range = TextRange(startOffset, endOffset.coerceAtLeast(startOffset)),
                    source = source
                )

            override fun describe(): String = "Configuration domain (${type.toKeyword()})"

            override val requiresExplicitEnd: Boolean = false
        }
    }
}
