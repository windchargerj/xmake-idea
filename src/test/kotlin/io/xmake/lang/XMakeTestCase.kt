package io.xmake.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.xmake.lang.declarations.import.ImportModuleFileResolver
import io.xmake.lang.declarations.source.ApiService
import io.xmake.lang.syntax.psi.XMakeLuaFile
import io.xmake.lang.syntax.psi.XMakeLuaIdentifier
import io.xmake.utils.info.XMakeApis
import io.xmake.utils.info.XMakeInfoManager
import kotlinx.serialization.json.Json
import java.io.File
import java.util.logging.Logger

abstract class XMakeTestCase : BasePlatformTestCase() {

    companion object {
        private val Json = Json { ignoreUnknownKeys = true }
        private val Log = Logger.getLogger(XMakeTestCase::class.java.name)
    }

    override fun getTestDataPath(): String {
        val resource = javaClass.getResource("/testData")
        requireNotNull(resource) { "testData directory not found in classpath" }
        return File(resource.toURI()).absolutePath
    }

    override fun getBasePath(): String = "testData"

    override fun setUp() {
        super.setUp()
        loadXMakeApis()
    }

    override fun tearDown() {
        try {
            XMakeInfoManager.getInstance(project).xmakeInfo.apis = XMakeApis()
        } finally {
            super.tearDown()
        }
    }

    protected fun loadXMakeApis() {
        val jsonFile = File(testDataPath, "xmake_apis.json")
        require(jsonFile.exists()) { "xmake_apis.json not found at $testDataPath" }

        try {
            val apis = Json.decodeFromString<XMakeApis>(jsonFile.readText())
            XMakeInfoManager.getInstance(project).xmakeInfo.apis = apis
        } catch (e: Exception) {
            Log.severe("Failed to load XMake APIs: ${e.message}")
            error("Failed to load XMake APIs: ${e.message}")
        }
    }

    protected fun configureXMakeLua(code: String): XMakeLuaFile {
        myFixture.configureByText("xmake.lua", code)
        return myFixture.file as XMakeLuaFile
    }

    protected fun identifierAtCaret(code: String): XMakeLuaIdentifier {
        configureXMakeLua(code)
        return identifierAtCaret()
    }

    protected fun identifierAtCaret(): XMakeLuaIdentifier {
        val leaf = elementAtCaret()
        return requireNotNull(
            PsiTreeUtil.getParentOfType(leaf, XMakeLuaIdentifier::class.java, false)
                ?: leaf as? XMakeLuaIdentifier
        )
    }

    protected fun elementAtCaret(file: PsiFile = myFixture.file): PsiElement {
        val caretOffset = myFixture.caretOffset
        return file.findElementAt(caretOffset)
            ?: file.findElementAt((caretOffset - 1).coerceAtLeast(0))
            ?: error("No PSI element at caret")
    }
}
