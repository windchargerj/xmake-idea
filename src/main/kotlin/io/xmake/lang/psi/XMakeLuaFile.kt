package io.xmake.lang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.xmake.icons.XMakeIcons
import io.xmake.lang.XMakeLuaLanguage
import io.xmake.lang.file.XMakeLuaFileType
import org.antlr.intellij.adaptor.psi.ScopeNode
import javax.swing.Icon

class XMakeLuaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, XMakeLuaLanguage), ScopeNode {

    override fun getFileType(): FileType {
        return XMakeLuaFileType.INSTANCE
    }

    override fun toString(): String {
        return "XMake Lua file"
    }

    override fun getIcon(flags: Int): Icon { /* Todo: Specify the correct icon. */
        return XMakeIcons.FILE
    }

    /** Return null since a file scope has no enclosing scope. It is
     * not itself in a scope.
     */
    override fun getContext(): ScopeNode? {
        return null
    }

    override fun resolve(element: PsiNamedElement): PsiElement? {
        return null
    }
}
