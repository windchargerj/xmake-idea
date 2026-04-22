package io.xmake.lang.codeInsight.annotation

import io.xmake.lang.XMakeTestCase

abstract class XMakeAnnotatorTestCase : XMakeTestCase() {

    protected fun highlight(code: String) {
        myFixture.configureByText("xmake.lua", code)
        myFixture.testHighlighting()
    }

    protected fun checkHighlighting(
        checkErrors: Boolean = true,
        checkWarnings: Boolean = true,
        checkInfos: Boolean = true
    ) {
        myFixture.checkHighlighting(checkErrors, checkWarnings, checkInfos)
    }
}

