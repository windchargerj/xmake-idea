package io.xmake.lang.declarations

import io.xmake.lang.XMakeTestCase

class KnownInstanceTypesTest : XMakeTestCase() {

    fun testDerivesReceiversFromScriptInstanceApiSurface() {
        val expected = setOf("target", "option", "rule", "package", "toolchain")
        val api = project.xmakeApi

        assertEquals(expected, KnownInstanceTypes.all(api))
        expected.forEach { name ->
            assertTrue(KnownInstanceTypes.contains(name, api))
        }
        assertFalse(KnownInstanceTypes.contains("task", api))
        assertFalse(KnownInstanceTypes.contains("component", api))
        assertFalse(KnownInstanceTypes.contains("path", api))
    }
}
