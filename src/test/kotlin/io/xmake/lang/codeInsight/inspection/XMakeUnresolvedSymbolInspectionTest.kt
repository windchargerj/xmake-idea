package io.xmake.lang.codeInsight.inspection

/**
 * Baseline unresolved-symbol diagnostics for stable lexical/domain behavior.
 *
 * This suite keeps Lua/xmake-visible baseline checks such as lexical scoping,
 * assignment behavior, table-field access, and directly available builtin
 * modules. Analysis-assisted cases live in
 * [XMakeUnresolvedSymbolInspectionAnalysisTest].
 */
class XMakeUnresolvedSymbolInspectionTest : XMakeInspectionTestCase() {

    fun testUndefinedVariable() = highlight("""
        local x = 1
        local copy = x
        local bad = <error descr="Unresolved variable 'y'">y</error>
    """.trimIndent())

    fun testFunctionParameterResolves() = highlight("""
        local function greet(name)
            local copy = name
        end
    """.trimIndent())

    fun testNestedBlockDoesNotLeakLocal() = highlight("""
        if true then
            local scoped = 1
        end
        local bad = <error descr="Unresolved variable 'scoped'">scoped</error>
    """.trimIndent())

    fun testAssignmentTargetIsNotUnresolved() = highlight("""
        value = 1
        local copy = value
    """.trimIndent())

    fun testMissingBaseInFieldAssignmentIsUnresolved() = highlight("""
        <error descr="Unresolved variable 'missing'">missing</error>.field = 1
    """.trimIndent())

    fun testTableFieldAccessIsNotUnresolved() = highlight("""
        local obj = {}
        local value = obj.field
    """.trimIndent())

    fun testModuleFunctionIsNotUnresolved() = highlight("""
        local joined = path.join("src", "main.c")
    """.trimIndent())

    fun testEditorRecoveryDescriptionApiCallHeadIsNotUnresolved() = assertNoUnresolvedAtCaret("""
        target("demo")
            set_ki<caret>nd
        target_end()
    """.trimIndent())

    fun testDifferentModuleFunctionMemberIsNotUnresolved() = highlight("""
        local joined = path.join("src", "main.c")
        local normalized = path.normalize("src/../main.c")
    """.trimIndent())

    fun testGenericForLoopVariablesResolveOnlyInsideLoop() = highlight("""
        for _, name in ipairs({"pthread", "dl"}) do
            local copy = name
        end
        local bad = <error descr="Unresolved variable 'name'">name</error>
    """.trimIndent())

    fun testNumericForLoopVariableResolvesOnlyInsideLoop() = highlight("""
        for i = 1, 3 do
            local copy = i
        end
        local bad = <error descr="Unresolved variable 'i'">i</error>
    """.trimIndent())

    fun testGotoLabelReferenceIsNotUnresolved() = highlight("""
        goto done
        ::done::
    """.trimIndent())
}

