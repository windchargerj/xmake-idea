package io.xmake.lang.declarations

import io.xmake.lang.XMakeTestCase

class KnownInstanceTypesTest : XMakeTestCase() {

    fun testExposesTargetLikeReceiversAsInstanceTypes() {
        assertTrue(KnownInstanceTypes.contains("target"))
        assertTrue(KnownInstanceTypes.contains("package"))
        assertTrue(KnownInstanceTypes.contains("component"))
        assertFalse(KnownInstanceTypes.contains("path"))
    }
}
