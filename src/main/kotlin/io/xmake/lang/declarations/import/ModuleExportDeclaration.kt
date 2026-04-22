package io.xmake.lang.declarations.import

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class ModuleExportDeclaration private constructor(
    val name: String,
    val kind: Kind,
    private val declarationPointer: SmartPsiElementPointer<PsiElement>?,
    private val declarationFilePointer: SmartPsiElementPointer<PsiFile>?,
    private val directSourceElement: PsiElement?,
    val declarationOffset: Int?,
    val declarationText: String?
) {
    enum class Kind {
        FUNCTION
    }

    val declarationElement: PsiElement?
        get() = declarationPointer?.element?.takeIf { it.isValid }
            ?: directSourceElement?.takeIf { it.isValid }
            ?: resolveFromFile()

    fun attachSourceFile(project: Project, file: VirtualFile): ModuleExportDeclaration {
        if (declarationFilePointer != null || directSourceElement != null) {
            return this
        }

        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return this
        val directSourceElement = resolveFromSourceFile(project, file)
        return ModuleExportDeclaration(
            name = name,
            kind = kind,
            declarationPointer = declarationPointer,
            declarationFilePointer = SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(psiFile),
            directSourceElement = directSourceElement,
            declarationOffset = declarationOffset,
            declarationText = declarationText
        )
    }

    companion object {
        fun fromPsi(
            name: String,
            declarationElement: PsiElement,
            kind: Kind = Kind.FUNCTION
        ): ModuleExportDeclaration =
            ModuleExportDeclaration(
                name = name,
                kind = kind,
                declarationPointer = SmartPointerManager.getInstance(declarationElement.project)
                    .createSmartPsiElementPointer(declarationElement),
                declarationFilePointer = SmartPointerManager.getInstance(declarationElement.project)
                    .createSmartPsiElementPointer(declarationElement.containingFile),
                directSourceElement = declarationElement,
                declarationOffset = declarationElement.textOffset,
                declarationText = declarationElement.text
            )

        fun detached(
            name: String,
            declarationOffset: Int? = null,
            declarationText: String? = name,
            kind: Kind = Kind.FUNCTION
        ): ModuleExportDeclaration =
            ModuleExportDeclaration(
                name = name,
                kind = kind,
                declarationPointer = null,
                declarationFilePointer = null,
                directSourceElement = null,
                declarationOffset = declarationOffset,
                declarationText = declarationText
            )
    }

    private fun resolveFromFile(): PsiElement? {
        val file = declarationFilePointer?.element ?: return null
        val offset = declarationOffset ?: return null
        if (offset !in 0 until file.textLength) {
            return null
        }
        val leaf = file.findElementAt(offset) ?: return null
        val identifier = PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
            ?: leaf as? XMakeLuaIdentifier
        return identifier?.takeIf { declarationText == null || it.text == declarationText }
    }

    private fun resolveFromSourceFile(project: Project, file: VirtualFile): PsiElement? {
        val offset = declarationOffset ?: return null
        val text = runCatching { file.inputStream.bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        if (offset !in text.indices) {
            return null
        }

        // Heuristic boundary: rebuild a lightweight PSI file when the source VirtualFile is outside project PSI.
        val parsedFile = PsiFileFactory.getInstance(project)
            .createFileFromText(file.name, XMakeLuaLanguage, text)
        val leaf = parsedFile.findElementAt(offset) ?: return null
        val identifier = PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
            ?: leaf as? XMakeLuaIdentifier
        return identifier?.takeIf { declarationText == null || it.text == declarationText }
    }
}
