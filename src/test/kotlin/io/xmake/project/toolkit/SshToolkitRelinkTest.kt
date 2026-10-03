package io.xmake.project.toolkit

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.remote.AuthType
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.testFramework.LightPlatformTestCase
import io.xmake.project.XMakeSettingsConfigurable
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

class SshToolkitRelinkTest : LightPlatformTestCase() {
    private lateinit var sshConfig: SshConfig

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        sshConfig = SshConfigManager.getInstance(project).register(
            false, "192.168.1.10", "22", "root", AuthType.PASSWORD,
            null, null, false, false, null, null,
        )
        assertNotNull("SSH config must be registered", sshConfig)
        // The SSH plugin flips AUTO_POPUP_JAVADOC_INFO while registering a config, which the
        // platform teardown would flag as settings damage; accept the post-registration baseline.
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())
    }

    /** A registration persisted by an older plugin version: no backendId, only the display name. */
    private fun legacySshToolkitState(name: String) = ToolkitManager.State().apply {
        registeredToolkits.add(
            Toolkit(
                name = name,
                host = ToolkitHost(ToolkitHostType.SSH),
                path = "/home/root/.local/bin/xmake",
                version = "v2.9.7",
            ),
        )
    }

    fun testRelinksLegacySshRegistrationAndRendersRow() {
        val shortName = sshConfig.presentableShortName
        ToolkitManager.getInstance().loadState(legacySshToolkitState(shortName))

        val registered = ToolkitManager.getInstance().registeredToolkits(project)
        assertEquals(1, registered.size)
        val toolkit = registered.single()
        assertTrue("host backend must be relinked", toolkit.host.hasBackend)
        assertEquals(sshConfig.id, toolkit.host.sshConfig?.id)
        assertEquals("SSH: $shortName:", settingsHostRowLabels().singleOrNull())
    }

    fun testUnresolvableHostLabelDoesNotRepeatHostType() {
        ToolkitManager.getInstance().loadState(legacySshToolkitState("jacky@removed-srv:22"))

        val registered = ToolkitManager.getInstance().registeredToolkits(project)
        assertEquals(1, registered.size)
        assertFalse(registered.single().host.hasBackend)

        val labels = settingsHostRowLabels()
        assertEquals(listOf("SSH: jacky@removed-srv:22:"), labels)
    }

    /** The host directory rows are the settings-page rows labeled with the host type prefix. */
    private fun settingsHostRowLabels(): List<String> {
        val configurable = XMakeSettingsConfigurable(project)
        val component = configurable.createComponent()
        assertNotNull(component)
        configurable.disposeUIResources()
        return collectLabels(component).filter { label -> label.startsWith("SSH:") }
    }

    private fun collectLabels(component: Component): List<String> {
        val labels = mutableListOf<String>()
        if (component is JLabel) labels.add(component.text)
        if (component is Container) component.components.forEach { labels.addAll(collectLabels(it)) }
        return labels
    }
}
