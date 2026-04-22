package io.xmake.lang.scope.issue

import com.intellij.openapi.util.TextRange

data class ScopeIssue(
    val kind: Kind,
    val range: TextRange,
    val message: String
) {
    enum class Kind {
        UNMATCHED_SCOPE_END,
        UNCLOSED_SCOPE
    }

    fun contains(offset: Int): Boolean {
        if (range.length == 0) {
            return offset == range.startOffset
        }
        return offset in range.startOffset until range.endOffset
    }
}
