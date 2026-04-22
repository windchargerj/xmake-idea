package io.xmake.lang.navigation

import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.XMakeTestCase
import io.xmake.lang.declarations.source.ApiDefinitionFactory
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.resolution.XMakeApiDeclarationService
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.utils.info.XMakeApis
import io.xmake.utils.info.XMakeInfoManager

class XMakeApiDeclarationServiceReloadTest : XMakeTestCase() {

    fun testSyntheticApiDeclarationsRefreshAfterApiReload() {
        val manager = XMakeInfoManager.getInstance(project)
        val service = XMakeApiDeclarationService.getInstance(project)
        val originalApi = ApiDefinitionFactory.create(manager.xmakeInfo.apis).first { it.fullName == "set_project" }
        assertNotNull(service.declarationTarget(originalApi))

        manager.xmakeInfo.apis = XMakeApis(
            descriptionBuiltinApis = listOf("fresh_global")
        )
        ApiService.getInstance(project).reload()

        assertNull(service.declarationTarget(originalApi))
        val refreshedApi = ApiDefinitionFactory.create(manager.xmakeInfo.apis).first { it.fullName == "fresh_global" }
        val refreshedTarget = service.declarationTarget(refreshedApi)
        assertNotNull(refreshedTarget)
        assertEquals("fresh_global", refreshedTarget?.text)
        assertEquals(XMakeApiDeclarationService.SYNTHETIC_FILE_NAME, refreshedTarget?.containingFile?.name)
    }

    fun testSyntheticApiDeclarationsRefreshWhenApisObjectReferenceStaysSame() {
        val manager = XMakeInfoManager.getInstance(project)
        val mutableDescriptionApis = mutableListOf("set_project")
        val sharedApis = XMakeApis(descriptionBuiltinApis = mutableDescriptionApis)
        manager.xmakeInfo.apis = sharedApis
        ApiService.getInstance(project).reload()

        val service = XMakeApiDeclarationService.getInstance(project)
        val oldApi = ApiDefinitionFactory.create(sharedApis).first { it.fullName == "set_project" }
        assertNotNull(service.declarationTarget(oldApi))

        mutableDescriptionApis.clear()
        manager.xmakeInfo.apis = sharedApis
        ApiService.getInstance(project).reload()

        assertNull(service.declarationTarget(oldApi))
    }

    fun testIsSyntheticApiDeclarationIgnoresRegularProjectFileIdentifier() {
        myFixture.configureByText(
            "xmake.lua",
            """
            set_pro<caret>ject("demo")
            """.trimIndent()
        )
        val identifier = PsiTreeUtil.findChildrenOfType(myFixture.file, XMakeLuaIdentifier::class.java)
            .firstOrNull { it.text == "set_project" }
            ?: error("Expected identifier set_project in xmake.lua")
        val service = XMakeApiDeclarationService.getInstance(project)
        assertFalse(service.isSyntheticApiDeclaration(identifier))
    }
}
