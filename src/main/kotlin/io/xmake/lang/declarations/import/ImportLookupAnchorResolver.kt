package io.xmake.lang.declarations.import

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

object ImportLookupAnchorResolver {

    fun mapToFile(file: XMakeLuaFile?, place: PsiElement?): PsiElement? {
        if (place == null) {
            return null
        }

        return when {
            file == null -> place
            place.containingFile == file -> place
            file.textLength <= 0 -> file
            else -> {
                val offset = place.textOffset.coerceIn(0, file.textLength - 1)
                file.findElementAt(offset) ?: file
            }
        }
    }

    fun anchor(place: PsiElement): PsiElement {
        (place as? XMakeLuaIdentifier)?.let { return it }
        PsiTreeUtil.getParentOfType(place, XMakeLuaIdentifier::class.java, false)?.let { return it }

        if (place.text == "." || place.text == ":") {
            resolveReceiverBefore(place)?.let { return it }
        }

        val previousLeaf = PsiTreeUtil.prevVisibleLeaf(place)
        if (previousLeaf?.text == "." || previousLeaf?.text == ":") {
            resolveReceiverBefore(previousLeaf)?.let { return it }
        }

        if (place.text.isBlank()) {
            val nearbyLeaf = PsiTreeUtil.prevVisibleLeaf(place) ?: PsiTreeUtil.nextVisibleLeaf(place)
            if (nearbyLeaf != null) {
                return anchor(nearbyLeaf)
            }
        }

        return place
    }

    private fun resolveReceiverBefore(separatorElement: PsiElement): PsiElement? {
        val receiverLeaf = PsiTreeUtil.prevVisibleLeaf(separatorElement) ?: return null
        return (receiverLeaf as? XMakeLuaIdentifier)
            ?: PsiTreeUtil.getParentOfType(receiverLeaf, XMakeLuaIdentifier::class.java, false)
    }
}
