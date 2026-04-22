package io.xmake.lang.declarations

import io.xmake.lang.declarations.model.XMakeType

object VerifiedMemberReturnTypes {

    private data class MemberRef(val receiverType: String, val memberName: String)

    data class Evidence(
        val source: String,
        val sourceUrl: String,
        val confirmedOn: String,
        val invalidatesWhen: String
    )

    data class Fact(
        val returnType: XMakeType,
        val evidence: Evidence
    )

    private val officialDocsEvidence = Evidence(
        source = "xmake official llms-full documentation",
        sourceUrl = "https://xmake.io/llms-full.txt",
        confirmedOn = "2026-04-26",
        invalidatesWhen = "xmake changes object method return semantics or generated API metadata becomes available"
    )

    private val memberReturnTypes: Map<MemberRef, Fact> = mapOf(
        MemberRef("target", "dep") to Fact(XMakeType.Instance("target"), officialDocsEvidence),
        MemberRef("target", "pkg") to Fact(XMakeType.Instance("package"), officialDocsEvidence),
        MemberRef("target", "rule") to Fact(XMakeType.Instance("rule"), officialDocsEvidence),
        MemberRef("rule", "clone") to Fact(XMakeType.Instance("rule"), officialDocsEvidence),
        MemberRef("package", "dep") to Fact(XMakeType.Instance("package"), officialDocsEvidence),
        MemberRef("option", "dep") to Fact(XMakeType.Instance("option"), officialDocsEvidence),
        MemberRef("target", "name") to Fact(XMakeType.Primitive.STRING, officialDocsEvidence),
        MemberRef("option", "name") to Fact(XMakeType.Primitive.STRING, officialDocsEvidence),
        MemberRef("package", "name") to Fact(XMakeType.Primitive.STRING, officialDocsEvidence),
        MemberRef("rule", "name") to Fact(XMakeType.Primitive.STRING, officialDocsEvidence),
        MemberRef("toolchain", "name") to Fact(XMakeType.Primitive.STRING, officialDocsEvidence),
    )

    fun resolve(receiverType: String, memberName: String): XMakeType? =
        resolveFact(receiverType, memberName)?.returnType

    fun resolveFact(receiverType: String, memberName: String): Fact? =
        memberReturnTypes[MemberRef(receiverType, memberName)]
}
