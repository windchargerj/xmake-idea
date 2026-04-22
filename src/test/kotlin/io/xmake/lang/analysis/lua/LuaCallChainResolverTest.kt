package io.xmake.lang.analysis.lua

import io.xmake.lang.XMakeTestCase

/**
 * Plugin-local PSI call-chain extraction coverage.
 *
 * These assertions protect the IDE's syntactic decomposition of member-call
 * chains; they are not xmake-specific specification tests.
 */
class LuaCallChainResolverTest : XMakeTestCase() {

    fun testResolvesModuleCallChain() {
        val identifier = identifierAtCaret(
            """
            local joined = foo.bar.ge<caret>t("key")
            """.trimIndent()
        )

        val chain = requireNotNull(LuaCallChainResolver.resolve(identifier))
        assertEquals(listOf("foo", "bar", "get"), chain.pathSegments)
        assertEquals(listOf(".", "."), chain.separators)
        assertEquals("get", chain.targetIdentifier.text)
    }

    fun testResolvesInstanceMethodChain() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (target)
                    target:a<caret>dd("defines", "DEBUG")
                end)
            target_end()
            """.trimIndent()
        )

        val chain = requireNotNull(LuaCallChainResolver.resolve(identifier))
        assertEquals(listOf("target", "add"), chain.pathSegments)
        assertEquals(listOf(":"), chain.separators)
        assertEquals("add", chain.targetIdentifier.text)
    }

    fun testReturnsNullForStandaloneFunctionCall() {
        val identifier = identifierAtCaret(
            """
            pri<caret>nt("hello")
            """.trimIndent()
        )

        assertNull(LuaCallChainResolver.resolve(identifier))
    }

}
