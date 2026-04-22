package io.xmake.lang.codeInsight.completion

import io.xmake.lang.XMakeTestCase

/**
 * Base class for xmake.lua completion tests.
 *
 * Extends [XMakeTestCase] and provides a compact DSL for completion assertions.
 *
 * Usage:
 *
 * ```kotlin
 * complete { "<caret>" }
 *     .expect("add_moduledirs", "add_packagedirs")
 *     .notExpect("assert", "catch")
 *
 * complete {
 *     """
 *     target("test")
 *         <caret>
 *     """.trimIndent()
 * }.expect("add_files")
 * ```
 */
abstract class XMakeCompletionTestCase : XMakeTestCase() {

    /**
     * Completion result wrapper with chainable assertions.
     */
    class CompletionResult(private val completions: List<String>) {

        /**
         * Asserts that the result contains the given items.
         */
        fun expect(vararg items: String): CompletionResult {
            assertContainsElements(completions, items.toList())
            return this
        }

        /**
         * Asserts that the result contains the given items.
         */
        /**
         * Asserts that the result does not contain the given items.
         */
        fun notExpect(vararg items: String): CompletionResult {
            assertDoesntContain(completions, items.toList())
            return this
        }

        /**
         * Asserts that the result does not contain the given items.
         */
        /**
         * Asserts that no completion items are available.
         */
        fun expectEmpty(): CompletionResult {
            assertTrue("Expected no completions but got: $completions", completions.isEmpty())
            return this
        }

    }

    /**
     * Runs completion against inline code.
     */
    protected fun complete(
        code: () -> String
    ): CompletionResult {
        myFixture.configureByText("xmake.lua", code())
        myFixture.completeBasic()
        return CompletionResult(myFixture.lookupElementStrings.orEmpty())
    }

}

