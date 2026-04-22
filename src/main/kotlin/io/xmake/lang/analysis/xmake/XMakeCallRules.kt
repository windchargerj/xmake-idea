package io.xmake.lang.analysis.xmake

import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.catalog.ApiIndex
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.resolution.QualifiedApiSelector

internal object XMakeCallRules {

    enum class CallShape {
        MODULE_MEMBER,
        INSTANCE_METHOD,
        UNKNOWN
    }

    fun classifyQualifiedCallShape(selector: QualifiedApiSelector): CallShape =
        when (selector) {
            is QualifiedApiSelector.ModuleFunction -> CallShape.MODULE_MEMBER
            is QualifiedApiSelector.InstanceMethod -> CallShape.INSTANCE_METHOD
        }

    fun classifyMemberCallShape(
        receiverType: XMakeType,
        separator: String
    ): CallShape =
        when {
            separator == "." && receiverType is XMakeType.Module -> CallShape.MODULE_MEMBER
            separator == ":" && receiverType is XMakeType.Instance -> CallShape.INSTANCE_METHOD
            else -> CallShape.UNKNOWN
        }

    fun isQualifiedCallKnown(
        api: XMakeApi,
        selector: QualifiedApiSelector,
        context: ApiLookupView
    ): Boolean =
        when (selector) {
            is QualifiedApiSelector.ModuleFunction ->
                api.indexedModuleFunctions(selector.modulePath, context).any { it.name == selector.functionName }

            is QualifiedApiSelector.InstanceMethod ->
                api.instanceApis(selector.instanceType, context).any { it.name == selector.methodName }
        }

    fun isQualifiedCallKnown(
        lookup: ApiIndex,
        selector: QualifiedApiSelector,
        context: ApiLookupView
    ): Boolean =
        when (selector) {
            is QualifiedApiSelector.ModuleFunction ->
                lookup.findApiByQualifiedSelector(selector, context) != null

            is QualifiedApiSelector.InstanceMethod ->
                lookup.instanceApis(selector.instanceType, context).any { it.name == selector.methodName }
        }

    fun isMemberCallKnown(
        api: XMakeApi,
        receiverType: XMakeType,
        memberName: String,
        separator: String,
        context: ApiLookupView
    ): Boolean =
        when (classifyMemberCallShape(receiverType, separator)) {
            CallShape.MODULE_MEMBER ->
                (receiverType as XMakeType.Module).let { moduleType ->
                    api.indexedModuleFunctions(moduleType.path, context).any { it.name == memberName }
                }

            CallShape.INSTANCE_METHOD ->
                (receiverType as XMakeType.Instance).let { instanceType ->
                    api.instanceApis(instanceType.typeName, context).any { it.name == memberName }
                }

            CallShape.UNKNOWN -> false
        }

    fun isMemberCallKnown(
        lookup: ApiIndex,
        receiverType: XMakeType,
        memberName: String,
        separator: String,
        context: ApiLookupView
    ): Boolean =
        when (classifyMemberCallShape(receiverType, separator)) {
            CallShape.MODULE_MEMBER ->
                (receiverType as XMakeType.Module).let { moduleType ->
                    lookup.moduleFunctions(moduleType.path, context).any { it.name == memberName }
                }

            CallShape.INSTANCE_METHOD ->
                (receiverType as XMakeType.Instance).let { instanceType ->
                    lookup.instanceApis(instanceType.typeName, context).any { it.name == memberName }
                }

            CallShape.UNKNOWN -> false
        }
}
