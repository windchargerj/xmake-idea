package io.xmake.lang.scope.model

import io.xmake.lang.scope.issue.ScopeIssue

data class XMakeState(
    val offset: Int,
    val region: XMakeRegion,
    val issues: List<ScopeIssue> = emptyList()
) {
    val domain: XMakeDomain
        get() = region.domain

    val root: XMakeRoot
        get() = region.root

    val source: XMakeRegion.Source
        get() = region.source
}
