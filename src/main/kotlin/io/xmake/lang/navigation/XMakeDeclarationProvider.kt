package io.xmake.lang.navigation

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.xmake.lang.syntax.psi.PsiPredicates
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeDeclarationProvider : PsiSymbolDeclarationProvider {

    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val identifier = element as? XMakeLuaIdentifier ?: return emptyList()
        if (!identifier.isDeclarationOwner()) {
            return emptyList()
        }
        return listOf(
            XMakePsiSymbolDeclaration(
                declarationElement = identifier,
                rangeInDeclaringElement = identifierRange(identifier, offsetInElement)
            )
        )
    }

    private fun identifierRange(identifier: XMakeLuaIdentifier, offsetInElement: Int): TextRange {
        val fullRange = TextRange(0, identifier.textLength)
        return if (offsetInElement >= 0 && fullRange.containsOffset(offsetInElement)) {
            TextRange(offsetInElement, offsetInElement + 1)
        } else {
            fullRange
        }
    }

    private fun XMakeLuaIdentifier.isDeclarationOwner(): Boolean =
        PsiPredicates.isLabel(this) ||
            PsiPredicates.isDeclaration(this) ||
            PsiPredicates.isFunctionDeclarationName(this)
}

private class XMakePsiSymbolDeclaration(
    private val declarationElement: PsiElement,
    private val rangeInDeclaringElement: TextRange
) : PsiSymbolDeclaration {

    override fun getDeclaringElement(): PsiElement = declarationElement

    override fun getRangeInDeclaringElement(): TextRange = rangeInDeclaringElement

    override fun getSymbol(): Symbol = PsiSymbolService.getInstance().asSymbol(declarationElement)
}
