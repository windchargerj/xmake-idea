package io.xmake.lang.completion

import com.intellij.codeInsight.completion.CompletionType
import io.xmake.lang.XMakeTestCase

abstract class XMakeCompletionTestCase : XMakeTestCase() {

    protected fun complete(
        testName: String,
        expected: List<String> = emptyList(),
        notExpected: List<String> = emptyList(),
        subdir: String = "completion",
        type: CompletionType = CompletionType.BASIC
    ) {
        myFixture.configureByFiles("$subdir/$testName.xmake.lua")
        myFixture.complete(type)
        val completions = myFixture.lookupElementStrings.orEmpty()

        if (expected.isNotEmpty()) {
            assertContainsElements(completions, expected)
        }
        if (notExpected.isNotEmpty()) {
            assertDoesntContain(completions, notExpected)
        }
    }

    protected fun complete(
        code: String,
        expected: List<String> = emptyList(),
        notExpected: List<String> = emptyList(),
        type: CompletionType = CompletionType.BASIC
    ) {
        myFixture.configureByText("xmake.lua", code)
        myFixture.complete(type)
        val completions = myFixture.lookupElementStrings.orEmpty()

        if (expected.isNotEmpty()) {
            assertContainsElements(completions, expected)
        }
        if (notExpected.isNotEmpty()) {
            assertDoesntContain(completions, notExpected)
        }
    }
}
