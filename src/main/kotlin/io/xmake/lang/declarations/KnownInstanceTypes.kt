package io.xmake.lang.declarations

import io.xmake.lang.scope.model.XMakeConfigurationDomainType

/**
 * Known xmake instance type names used for type inference.
 *
 * In xmake, configuration-domain keywords such as `target` and `package`
 * also act as receiver type names for xmake instance APIs in Script domain.
 */
object KnownInstanceTypes {
    val all: Set<String> =
        XMakeConfigurationDomainType.keywords.toSet() + "component"

    fun contains(name: String): Boolean = name in all
}
