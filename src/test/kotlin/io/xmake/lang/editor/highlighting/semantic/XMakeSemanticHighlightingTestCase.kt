package io.xmake.lang.editor.highlighting.semantic

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.xmake.lang.XMakeTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.fail

/**
 * Shared caret-based semantic highlighting DSL.
 *
 * Scenarios stay inline in each test; this base class only provides a fluent
 * assertion wrapper around the caret's overlapping highlight entries.
 */
abstract class XMakeSemanticHighlightingTestCase : XMakeTestCase() {

    protected fun highlighting(code: () -> String): HighlightingResult {
        myFixture.configureByText("xmake.lua", code())
        return HighlightingResult(
            offset = myFixture.caretOffset,
            infos = myFixture.doHighlighting()
        )
    }

    protected class HighlightingResult(
        private val offset: Int,
        private val infos: List<HighlightInfo>,
    ) {
        fun expect(expected: TextAttributesKey): HighlightingResult {
            val matchingInfo = overlapping.firstOrNull { info ->
                info.forcedTextAttributesKey == expected
            }

            if (matchingInfo == null) {
                fail(buildFailureMessage(expected))
            }
            return this
        }

        fun notExpect(vararg unexpected: TextAttributesKey): HighlightingResult {
            unexpected.forEach { key ->
                assertFalse(
                    "Did not expect ${key.externalName} at caret. Actual: ${overlapping.map { it.forcedTextAttributesKey?.externalName }}",
                    overlapping.any { it.forcedTextAttributesKey == key }
                )
            }
            return this
        }

        private val overlapping: List<HighlightInfo>
            get() = infos.filter { it.startOffset <= offset && offset < it.endOffset }

        private fun buildFailureMessage(expected: TextAttributesKey): String {
            if (overlapping.isEmpty()) {
                return "No highlighting info at caret offset $offset"
            }
            return "Expected ${expected.externalName}, actual: ${overlapping.map { it.forcedTextAttributesKey?.externalName }}"
        }
    }
}
