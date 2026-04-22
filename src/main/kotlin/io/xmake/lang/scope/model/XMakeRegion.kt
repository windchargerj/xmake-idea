package io.xmake.lang.scope.model

import com.intellij.openapi.util.TextRange

data class XMakeRegion(
    val domain: XMakeDomain,
    val root: XMakeRoot,
    val range: TextRange,
    val source: Source = Source.PSI
) {
    enum class Source {
        PSI
    }

    val startOffset: Int
        get() = range.startOffset

    val endOffset: Int
        get() = range.endOffset

    fun contains(offset: Int): Boolean {
        if (range.length == 0) {
            return offset == range.startOffset
        }
        return offset in range.startOffset until range.endOffset
    }
}
