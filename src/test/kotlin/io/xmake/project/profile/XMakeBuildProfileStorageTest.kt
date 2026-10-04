package io.xmake.project.profile

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LightPlatformTestCase
import io.xmake.project.profile.XMakeBuildProfileStorage.PROJECT
import io.xmake.project.profile.XMakeBuildProfileStorage.SHARED
import io.xmake.test.FrameworkTestNoise

class XMakeBuildProfileStorageTest : LightPlatformTestCase() {
    private lateinit var manager: XMakeBuildProfileManager
    private lateinit var shared: XMakeSharedBuildProfiles
    private var noiseToken: AccessToken? = null

    @Throws(Exception::class)
    override fun setUp() {
        noiseToken = FrameworkTestNoise.suppressDisposalNoise()
        try {
            super.setUp()
            manager = project.xmakeBuildProfiles
            shared = XMakeSharedBuildProfiles.getInstance()
            shared.replaceProfiles(listOf(XMakeBuildProfile(name = "Shared Default")))
            manager.switchStorage(PROJECT)
            manager.replaceProfiles(listOf(XMakeBuildProfile(name = "Local Default")))
            // Platform components loaded during the test flip AUTO_POPUP_JAVADOC_INFO, which the
            // teardown would flag as settings damage; accept the post-setup baseline.
            setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())
        } catch (error: Exception) {
            noiseToken?.finish()
            noiseToken = null
            throw error
        }
    }

    @Throws(Exception::class)
    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            noiseToken?.finish()
            noiseToken = null
        }
    }

    fun testSwitchToSharedMergesProjectProfiles() {
        val localProfile = manager.profiles.single()
        val sharedProfileBefore = shared.profiles.single()

        manager.switchStorage(SHARED)

        assertEquals(SHARED, manager.storage)
        val sharedIds = shared.profiles.map(XMakeBuildProfile::id).toSet()
        assertTrue(sharedIds.contains(localProfile.id))
        assertTrue(sharedIds.contains(sharedProfileBefore.id))
        assertEquals(2, shared.profiles.size)
        assertEquals(
            shared.profiles.map(XMakeBuildProfile::id).toSet(),
            manager.profiles.map(XMakeBuildProfile::id).toSet(),
        )
    }

    fun testEditsWhileSharedLandInTheSharedStore() {
        manager.switchStorage(SHARED)
        val edited = shared.profiles.map { profile -> profile.copy(verbose = !profile.verbose) }

        manager.replaceProfiles(edited)

        assertEquals(edited.map(XMakeBuildProfile::id), shared.profiles.map(XMakeBuildProfile::id))
        assertEquals(shared.profiles, manager.profiles)
    }

    fun testSharedEditsFromOutsideRefreshTheProject() {
        manager.switchStorage(SHARED)
        val replacement = listOf(XMakeBuildProfile(name = "From Another Project"))

        // Another project (or window) rewrites the shared list directly.
        shared.replaceProfiles(replacement)

        assertEquals(replacement, manager.profiles)
    }

    fun testSwitchBackToProjectCopiesTheSharedList() {
        manager.switchStorage(SHARED)
        val sharedSnapshot = shared.profiles

        manager.switchStorage(PROJECT)

        assertEquals(PROJECT, manager.storage)
        assertEquals(sharedSnapshot, manager.profiles)
        // The shared list itself is untouched for the other projects using it.
        assertEquals(sharedSnapshot, shared.profiles)
        manager.replaceProfiles(listOf(XMakeBuildProfile(name = "Local Only")))
        assertEquals(sharedSnapshot, shared.profiles)
    }

    fun testProjectStateElementOmitsProfilesWhileShared() {
        manager.switchStorage(SHARED)
        assertEquals("SHARED", manager.getState().getAttributeValue("storage"))
        assertTrue(XMakeBuildProfileXml.readProfiles(manager.getState()).isEmpty())

        manager.switchStorage(PROJECT)
        assertEquals("PROJECT", manager.getState().getAttributeValue("storage"))
        assertTrue(XMakeBuildProfileXml.readProfiles(manager.getState()).isNotEmpty())
    }

    fun testPendingLegacyDirectoriesSurviveSharedStorageSave() {
        // A pre-profile-era working directory whose toolkit is not registered stays pending. In
        // the per-profile legacy format the toolkit reference lives on the profile element.
        val profile = XMakeBuildProfile(name = "Legacy Holder", toolkitId = "missing-toolkit")
        val pending = mapOf(
            profile.id to XMakeBuildProfileXml.PendingLegacyDirectory(
                toolkitId = "missing-toolkit",
                directory = "/remote/project",
            ),
        )
        val projectState = org.jdom.Element("XMakeBuildProfiles").also { element ->
            XMakeBuildProfileXml.writeProfiles(element, listOf(profile))
            XMakeBuildProfileXml.writeLegacyWorkingDirectories(element, pending)
        }
        manager.loadState(projectState)
        assertEquals(PROJECT, manager.storage)

        manager.switchStorage(SHARED)
        val sharedState = manager.getState()

        assertTrue(XMakeBuildProfileXml.readProfiles(sharedState).isEmpty())
        assertEquals(pending, XMakeBuildProfileXml.readStandalonePendingLegacyDirectories(sharedState))

        // Reloading such a state keeps the entry pending instead of dropping it.
        manager.loadState(sharedState)
        assertEquals(pending, XMakeBuildProfileXml.readStandalonePendingLegacyDirectories(manager.getState()))
    }
}
