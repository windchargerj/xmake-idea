package io.xmake.lang.scope.model

import junit.framework.TestCase
import java.util.Locale

class XMakeConfigurationDomainTypeTest : TestCase() {

    fun testKeywordNormalizationUsesRootLocale() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            for (type in XMakeConfigurationDomainType.entries) {
                val keyword = type.toKeyword()
                val endKeyword = type.toEndKeyword()

                assertEquals(type.name.lowercase(Locale.ROOT), keyword)
                assertEquals("${keyword}_end", endKeyword)
                assertEquals(type, XMakeConfigurationDomainType.fromKeyword(keyword))
                assertEquals(type, XMakeConfigurationDomainType.fromKeyword(keyword.uppercase()))
                assertEquals(type, XMakeConfigurationDomainType.fromEndKeyword(endKeyword))
                assertEquals(type, XMakeConfigurationDomainType.fromEndKeyword(endKeyword.uppercase()))
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
