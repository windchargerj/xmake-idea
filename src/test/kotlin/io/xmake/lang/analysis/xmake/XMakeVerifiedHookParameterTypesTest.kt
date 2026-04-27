package io.xmake.lang.analysis.xmake

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.model.XMakeType
import io.xmake.lang.declarations.xmakeApi

class XMakeVerifiedHookParameterTypesTest : XMakeTestCase() {

    fun testReturnsVerifiedPrimaryTargetHookParameterType() {
        val identifier = identifierAtCaret(
            """
            target("demo")
                on_load(function (ta<caret>rget)
                    target:name()
                end)
            target_end()
            """.trimIndent()
        )

        assertEquals(
            XMakeType.Instance("target"),
            XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi)
        )
    }

    fun testDoesNotReturnHookParameterTypeWithoutApiSurface() {
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

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi))
    }

    fun testDoesNotReturnRuleHookPrimaryParameterType() {
        val identifier = identifierAtCaret(
            """
            rule("demo")
                before_build(function (tar<caret>get)
                    target:name()
                end)
            rule_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi))
    }

    fun testDoesNotReturnProjectRuleHookPrimaryParameterType() {
        val identifier = identifierAtCaret(
            """
            rule("demo")
                set_kind("project")
                after_build(function (o<caret>pt)
                    opt
                end)
            rule_end()
            """.trimIndent()
        )

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi))
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

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi))
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

        assertNull(XMakeVerifiedHookParameterTypes.resolveVerifiedParameterType(identifier, project.xmakeApi))
    }

}
