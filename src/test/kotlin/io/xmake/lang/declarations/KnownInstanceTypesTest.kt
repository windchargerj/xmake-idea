package io.xmake.lang.declarations

import io.xmake.lang.XMakeTestCase
import io.xmake.lang.scope.model.XMakeConfigurationDomainType

class KnownInstanceTypesTest : XMakeTestCase() {

    fun testExposesAllCurrentConfigurationDomainReceiversAndComponent() {
        val expected = XMakeConfigurationDomainType.keywords.toSet() + "component"

        assertEquals(7, KnownInstanceTypes.all.size)
        assertEquals(expected, KnownInstanceTypes.all)
        expected.forEach { name ->
            assertTrue(KnownInstanceTypes.contains(name))
        }
        assertFalse(KnownInstanceTypes.contains("path"))
    }
}
