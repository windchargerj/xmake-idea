package io.xmake.lang.scope.model

sealed interface XMakeRoot {

    data object Global : XMakeRoot

    data class Namespace(
        val parent: XMakeRoot = Global,
        val name: String? = null,
        val identity: String = name ?: "<anonymous>"
    ) : XMakeRoot {
        val path: List<String>
            get() = when (parent) {
                is Namespace -> parent.path + listOfNotNull(name)
                is Global -> listOfNotNull(name)
            }

        val identityPath: List<String>
            get() = when (parent) {
                is Namespace -> parent.identityPath + identity
                is Global -> listOf(identity)
            }
    }
}
