package io.xmake.lang

import com.intellij.psi.tree.IElementType
import io.xmake.lang.psi.xmake.DomainScope.DomainType
import java.util.concurrent.ConcurrentHashMap

object XMakeLanguageIElementTypes {

    open class XMakeLuaIElementType(debugName: String) : IElementType(debugName, XMakeLuaLanguage.INSTANCE)

    val SCRIPT_SCOPE = XMakeLuaIElementType("ScriptScope")
    val GLOBAL_SCOPE = XMakeLuaIElementType("GlobalScope")

    private val domainScopeTypes: Map<DomainType, DomainScopeType> =
        DomainType.entries.associateWith { DomainScopeType(it) }

    private val namespaceScopeTypes = ConcurrentHashMap<String, NamespaceScopeType>()

    fun domainScope(type: DomainType): DomainScopeType = domainScopeTypes.getValue(type)

    fun domainScope(typeName: String): DomainScopeType? {
        val type = DomainType.fromString(typeName) ?: return null
        return domainScopeTypes[type]
    }

    fun namespaceScope(namespace: String): NamespaceScopeType {
        return namespaceScopeTypes.getOrPut(namespace) { NamespaceScopeType(namespace) }
    }

    class DomainScopeType(val domainType: DomainType) : XMakeLuaIElementType("DomainScope_${domainType.name}") {
        override fun equals(other: Any?): Boolean = other is DomainScopeType && domainType == other.domainType
        override fun hashCode(): Int = domainType.hashCode()
    }

    class NamespaceScopeType(val namespace: String) : XMakeLuaIElementType("NamespaceScope_$namespace") {
        override fun equals(other: Any?): Boolean = other is NamespaceScopeType && namespace == other.namespace
        override fun hashCode(): Int = namespace.hashCode()
    }
}
