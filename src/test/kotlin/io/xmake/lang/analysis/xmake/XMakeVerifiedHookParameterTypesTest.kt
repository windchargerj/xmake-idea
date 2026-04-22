package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase

class XMakeVerifiedHookParameterTypesTest : XMakeTestCase() {

    fun testDoesNotReturnUnverifiedPrimaryHookParameterType() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (ta<caret>rget)
                    target:name()
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier))
    }

    fun testDoesNotReturnUnverifiedNonPrimaryFileHookParameterType() {
        val identifier = identifierAtCaret(
            """
            rule("demo")
                before_build_file(function (target, source<caret>file, opt)
                    sourcefile
                end)
            rule_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier))
    }

    fun testDoesNotReturnUnverifiedJobgraphHookParameterType() {
        val identifier = identifierAtCaret(
            """
            rule("demo")
                on_build_files(function (target, job<caret>graph, sourcebatch, opt)
                    jobgraph
                end, {jobgraph = true})
            rule_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier))
    }

    fun testDoesNotReturnUnknownHookParameterType() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_custom_file(function (target, source<caret>file, opt)
                    sourcefile
                end)
            target_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier))
    }

}
