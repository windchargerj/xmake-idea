package io.xmake.lang.psi.xmake

import com.intellij.lang.ASTNode
import io.xmake.lang.api.model.BuildScope

class GlobalScope(node: ASTNode) : DescriptionScope(node) {
    override val xmakeScope: BuildScope get() = BuildScope.Global
}
