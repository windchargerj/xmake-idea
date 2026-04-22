package io.xmake.lang.syntax.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import io.xmake.icons.XMakeIcons
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.file.XMakeLuaFileType
import javax.swing.Icon

class XMakeLuaFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, XMakeLuaLanguage) {

    override fun getFileType(): FileType {
        return XMakeLuaFileType.INSTANCE
    }

    override fun toString(): String {
        return "XMake Lua file"
    }

    override fun getIcon(flags: Int): Icon {
        return XMakeIcons.FILE
    }
}
