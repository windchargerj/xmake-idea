package io.xmake.lang.syntax.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import org.antlr.intellij.adaptor.psi.ANTLRPsiLeafNode

class XMakeLuaIdentifier(type: IElementType?, text: CharSequence?) : ANTLRPsiLeafNode(type, text),
    PsiNamedElement,
    ContributedReferenceHost {

    override fun getName(): String = text

    // ANTLR leaf nodes need to opt in by implementing ContributedReferenceHost
    // and delegating to the platform registry explicitly.
    override fun getReference(): PsiReference? = getReferences().firstOrNull()

    override fun getReferences(): Array<PsiReference> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)

    @Throws(IncorrectOperationException::class)
    override fun setName(name: String): PsiElement {
        val file = containingFile ?: return this
        val startOffset = textRange.startOffset
        val endOffset = textRange.endOffset

        return WriteCommandAction.runWriteCommandAction<PsiElement>(project) {
            val documentManager = PsiDocumentManager.getInstance(project)
            val document = documentManager.getDocument(file) ?: return@runWriteCommandAction this@XMakeLuaIdentifier

            document.replaceString(startOffset, endOffset, name)
            documentManager.commitDocument(document)

            val replacementLeaf = file.findElementAt(startOffset) ?: return@runWriteCommandAction this@XMakeLuaIdentifier
            PsiTreeUtil.getParentOfType(replacementLeaf, XMakeLuaIdentifier::class.java, false)
                ?: (replacementLeaf as? XMakeLuaIdentifier)
                ?: this@XMakeLuaIdentifier
        }
    }
}
