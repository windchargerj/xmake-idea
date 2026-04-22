package io.xmake.lang.codeInsight.inspection

import io.xmake.lang.XMakeTestCase
import org.junit.Assert.assertTrue

abstract class XMakeInspectionTestCase : XMakeTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(XMakeUnresolvedSymbolInspection())
    }

    protected fun highlight(code: String) {
        myFixture.configureByText("xmake.lua", code)
        myFixture.testHighlighting()
    }

    protected fun assertNoUnresolvedAtCaret(code: String) {
        myFixture.configureByText("xmake.lua", code)
        val offset = myFixture.caretOffset
        val unresolved = myFixture.doHighlighting().filter { info ->
            info.startOffset <= offset &&
                offset < info.endOffset &&
                info.description?.startsWith("Unresolved ") == true
        }

        assertTrue(
            "Unexpected unresolved highlighting at caret: ${unresolved.mapNotNull { it.description }}",
            unresolved.isEmpty()
        )
    }
}

