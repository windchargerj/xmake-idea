package io.xmake.lang.navigation.structure

import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import io.xmake.lang.syntax.psi.XMakeLuaFile

class LuaStructureViewModel
    (root: XMakeLuaFile) : StructureViewModelBase(root, LuaStructureViewRootElement(root)), ElementInfoProvider {
    override fun getSorters(): Array<Sorter> {
        return arrayOf(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        return !isAlwaysShowsPlus(element)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is XMakeLuaFile
    }
}

