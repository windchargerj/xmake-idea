package io.xmake.lang.resolution.reference

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import io.xmake.lang.syntax.XMakeLuaLanguage
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier

class XMakeReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XMakeLuaIdentifier::class.java)
                .withLanguage(XMakeLuaLanguage.INSTANCE),
            XMakeReferenceProvider()
        )
    }
}
