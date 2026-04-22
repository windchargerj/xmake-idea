package io.xmake.lang.analysis.xmake

import io.xmake.lang.declarations.VerifiedMemberReturnTypes
import io.xmake.lang.declarations.model.XMakeType
import junit.framework.TestCase

/**
 * Validates evidence-backed object return-type facts used by analysis features.
 *
 * Individual entries are grounded in xmake docs/runtime examples, but the [XMakeType] lattice
 * and unknown-member behavior are plugin-local typing rather than direct xmake-spec output.
 */
class XMakeObjectTypingTest : TestCase() {

    fun testResolvesTargetDependentObjects() {
        assertEquals(
            XMakeType.Instance("target"),
            VerifiedMemberReturnTypes.resolve("target", "dep")
        )
        assertEquals(
            XMakeType.Instance("package"),
            VerifiedMemberReturnTypes.resolve("target", "pkg")
        )
        assertEquals(
            XMakeType.Instance("rule"),
            VerifiedMemberReturnTypes.resolve("target", "rule")
        )
    }

    fun testResolvesPackageDepReturnType() {
        assertEquals(
            XMakeType.Instance("package"),
            VerifiedMemberReturnTypes.resolve("package", "dep")
        )
    }

    fun testResolvesDocumentedOptionDepReturnType() {
        assertEquals(
            XMakeType.Instance("option"),
            VerifiedMemberReturnTypes.resolve("option", "dep")
        )
    }

    fun testResolvesRuleCloneReturnType() {
        assertEquals(
            XMakeType.Instance("rule"),
            VerifiedMemberReturnTypes.resolve("rule", "clone")
        )
    }

    fun testReturnsStringForDocumentedNameGetter() {
        assertEquals(
            XMakeType.Primitive.STRING,
            VerifiedMemberReturnTypes.resolve("target", "name")
        )
    }

    fun testVerifiedReturnTypeFactsCarryEvidence() {
        val fact = requireNotNull(VerifiedMemberReturnTypes.resolveFact("target", "dep"))

        assertEquals(XMakeType.Instance("target"), fact.returnType)
        assertEquals("https://xmake.io/llms-full.txt", fact.evidence.sourceUrl)
        assertTrue(fact.evidence.confirmedOn.isNotBlank())
        assertTrue(fact.evidence.invalidatesWhen.isNotBlank())
    }

    fun testUnknownMembersReturnNull() {
        assertNull(VerifiedMemberReturnTypes.resolve("target", "enabled"))
        assertNull(VerifiedMemberReturnTypes.resolve("path", "join"))
    }
}
