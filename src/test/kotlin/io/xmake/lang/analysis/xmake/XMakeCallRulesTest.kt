package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.ApiLookupView
import io.xmake.lang.declarations.XMakeApi
import io.xmake.lang.declarations.resolution.QualifiedApiSelector
import io.xmake.lang.declarations.model.XMakeType

/**
 * Plugin-local call-kind resolution on top of receiver types and API lookup.
 *
 * This suite validates the repository's [XMakeCallRules] contract rather
 * than asserting direct xmake-spec behavior.
 */
class XMakeCallRulesTest : XMakeTestCase() {

    fun testClassifiesQualifiedCallShapeFromLuaReceiverKind() {
        val lookup = XMakeApi.getInstance(project).lookup

        val moduleSelector = QualifiedApiSelector.ModuleFunction(modulePath = "path", functionName = "join")
        val instanceSelector = QualifiedApiSelector.InstanceMethod(instanceType = "target", methodName = "add")

        assertEquals(XMakeCallRules.CallShape.MODULE_MEMBER, XMakeCallRules.classifyQualifiedCallShape(moduleSelector))
        assertEquals(XMakeCallRules.CallShape.INSTANCE_METHOD, XMakeCallRules.classifyQualifiedCallShape(instanceSelector))
        assertTrue(XMakeCallRules.isQualifiedCallKnown(lookup, moduleSelector, ApiLookupView.SCRIPT_GLOBAL_ROOT))
        assertTrue(XMakeCallRules.isQualifiedCallKnown(lookup, instanceSelector, ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }

    fun testClassifiesMemberCallShapeFromLuaReceiverType() {
        val lookup = XMakeApi.getInstance(project).lookup

        val moduleReceiver = XMakeType.Module("path", ApiLookupView.SCRIPT_GLOBAL_ROOT)
        val instanceReceiver = XMakeType.Instance("target")

        assertEquals(
            XMakeCallRules.CallShape.MODULE_MEMBER,
            XMakeCallRules.classifyMemberCallShape(moduleReceiver, ".")
        )
        assertEquals(
            XMakeCallRules.CallShape.INSTANCE_METHOD,
            XMakeCallRules.classifyMemberCallShape(instanceReceiver, ":")
        )
        assertTrue(
            XMakeCallRules.isMemberCallKnown(
                lookup,
                moduleReceiver,
                "join",
                ".",
                ApiLookupView.SCRIPT_GLOBAL_ROOT
            )
        )
        assertTrue(
            XMakeCallRules.isMemberCallKnown(
                lookup,
                instanceReceiver,
                "add",
                ":",
                ApiLookupView.SCRIPT_GLOBAL_ROOT
            )
        )
    }

    fun testRejectsMismatchedReceiverKinds() {
        val lookup = XMakeApi.getInstance(project).lookup

        val invalidModuleReceiver = XMakeType.Instance("target")
        val invalidInstanceReceiver = XMakeType.Module("path", ApiLookupView.SCRIPT_GLOBAL_ROOT)

        assertEquals(
            XMakeCallRules.CallShape.UNKNOWN,
            XMakeCallRules.classifyMemberCallShape(invalidModuleReceiver, ".")
        )
        assertEquals(
            XMakeCallRules.CallShape.UNKNOWN,
            XMakeCallRules.classifyMemberCallShape(invalidInstanceReceiver, ":")
        )
        assertFalse(
            XMakeCallRules.isMemberCallKnown(
                lookup,
                invalidModuleReceiver,
                "join",
                ".",
                ApiLookupView.SCRIPT_GLOBAL_ROOT
            )
        )
        assertFalse(
            XMakeCallRules.isMemberCallKnown(
                lookup,
                invalidInstanceReceiver,
                "add",
                ":",
                ApiLookupView.SCRIPT_GLOBAL_ROOT
            )
        )
    }

    fun testSeparatesLuaCallShapeFromXMakeMemberValidation() {
        val lookup = XMakeApi.getInstance(project).lookup
        val selector = QualifiedApiSelector.InstanceMethod(
            instanceType = "target",
            methodName = "missing_member"
        )

        assertEquals(XMakeCallRules.CallShape.INSTANCE_METHOD, XMakeCallRules.classifyQualifiedCallShape(selector))
        assertFalse(XMakeCallRules.isQualifiedCallKnown(lookup, selector, ApiLookupView.SCRIPT_GLOBAL_ROOT))
    }
}
