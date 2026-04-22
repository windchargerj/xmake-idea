package io.xmake.lang.scope.model

import junit.framework.TestCase
import java.util.Locale

class XMakeConfigurationDomainTypeTest : TestCase() {

    fun testKeywordNormalizationUsesRootLocale() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals("option", XMakeConfigurationDomainType.OPTION.toKeyword())
            assertEquals("toolchain_end", XMakeConfigurationDomainType.TOOLCHAIN.toEndKeyword())
            assertEquals(XMakeConfigurationDomainType.OPTION, XMakeConfigurationDomainType.fromKeyword("OPTION"))
            assertEquals(XMakeConfigurationDomainType.TOOLCHAIN, XMakeConfigurationDomainType.fromEndKeyword("TOOLCHAIN_END"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
