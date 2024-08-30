package io.xmake.project

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessNotCreatedException
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import io.xmake.icons.XMakeIcons
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.XMakeRunConfigurationType
import io.xmake.utils.execute.SyncDirection
import io.xmake.utils.execute.createProcess
import io.xmake.utils.execute.runProcess
import io.xmake.utils.execute.transferFolderByToolkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.swing.Icon

@Deprecated("Please refer to the relevant content in folder io/xmake/project/wizard.")
class XMakeDirectoryProjectGenerator :
    DirectoryProjectGeneratorBase<XMakeConfigData>(), CustomStepProjectGenerator<XMakeConfigData> {

    private val scope = CoroutineScope(Dispatchers.Default)

    private var peer: XMakeProjectGeneratorPeer? = null

    override fun getName(): String = "XMake"
    override fun getLogo(): Icon = XMakeIcons.XMAKE
    override fun createPeer(): ProjectGeneratorPeer<XMakeConfigData> = XMakeProjectGeneratorPeer().also { peer = it }

    override fun generateProject(project: Project, baseDir: VirtualFile, data: XMakeConfigData, module: Module) {
        // get content entry path
        val contentEntryPath = baseDir.canonicalPath ?: return

        /* create empty project
         * @note we muse use ioRunv instead of Runv to read all output, otherwise it will wait forever on windows
         */
        val tmpdir = "$contentEntryPath.dir"

        val dir = if (!data.toolkit!!.isOnRemote) tmpdir else data.remotePath!!

        val workingDir = if (!data.toolkit.isOnRemote) contentEntryPath else data.remotePath!!

        Log.debug("dir: $dir")

        val command = listOf(
            data.toolkit.path,
            "create",
            "-P",
            dir,
            "-l",
            data.languagesModel,
            "-t",
            data.kindsModel
        )

        val commandLine: GeneralCommandLine = GeneralCommandLine(command)
            .withWorkDirectory(workingDir)
            .withCharset(Charsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val results = try {
            val (result, _) = runBlocking(Dispatchers.IO) {
                return@runBlocking runProcess(commandLine.createProcess(data.toolkit))
            }
            result.getOrDefault("").split(Regex("\\s+"))
        } catch (e: ProcessNotCreatedException) {
            emptyList()
        }

        Log.info("results: $results")

        with(data.toolkit) {
            if (!isOnRemote) {
                val tmpFile = File(tmpdir)
                if (tmpFile.exists()) {
                    tmpFile.copyRecursively(File(contentEntryPath), true)
                    tmpFile.deleteRecursively()
                }
            } else {
                transferFolderByToolkit(
                    project,
                    this,
                    SyncDirection.UPSTREAM_TO_LOCAL,
                    directoryPath = data.remotePath!!,
                    null
                )
            }
        }

        val runManager = RunManager.getInstance(project)

        val configSettings =
            runManager.createConfiguration(project.name, XMakeRunConfigurationType.getInstance().factory)
        runManager.addConfiguration(configSettings.apply {
            (configuration as XMakeRunConfiguration).apply {
                runToolkit = data.toolkit
                runWorkingDir = workingDir
            }
        })
        runManager.selectedConfiguration = runManager.allSettings.first()

        val contentEntry = module.rootManager.modifiableModel.addContentEntry(contentEntryPath)
        // add source root
        val sourceRoot = File(contentEntryPath).path
        contentEntry.addSourceFolder(sourceRoot, false)

    }

    override fun createStep(
        projectGenerator: DirectoryProjectGenerator<XMakeConfigData>,
        callback: AbstractNewProjectStep.AbstractCallback<XMakeConfigData>
    ): AbstractActionWithPanel = XMakeProjectSettingsStep(projectGenerator)

    companion object {
        val Log = logger<XMakeDirectoryProjectGenerator>()
    }

}
