package io.xmake.project.toolkit

import com.intellij.execution.RunManager
import com.intellij.execution.processTools.getBareExecutionResult
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.util.PlatformUtils
import com.intellij.util.xmlb.annotations.XCollection
import io.xmake.project.toolkit.ToolkitHostType.*
import io.xmake.run.XMakeRunConfiguration
import io.xmake.utils.execute.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

@Service
@State(name = "toolkits", storages = [Storage("xmakeToolkits.xml")])
class ToolkitManager(private val scope: CoroutineScope) : PersistentStateComponent<ToolkitManager.State> {

    val fetchedToolkitsSet = mutableSetOf<Toolkit>()
    private lateinit var detectionJob: Job
    private lateinit var validateJob: Job
    private var storage: State = State()
    private val listenerList = mutableListOf<ToolkitDetectedListener>()

    class State{
        @XCollection(propertyElementName = "registeredToolKits")
        val registeredToolkits = mutableSetOf<Toolkit>()
        var lastSelectedToolkitId: String? = null
    }

    interface ToolkitDetectedListener : EventListener {
        fun onToolkitDetected(e: ToolkitDetectEvent)
        fun onAllToolkitsDetected()
    }

    class ToolkitDetectEvent(source: Toolkit) : EventObject(source)

    init {
        scope.launch {
            // Cache the list of installed distributions
            WslDistributionManager.getInstance().installedDistributions
        }
    }

    private fun toolkitHostFlow(project: Project? = null): Flow<ToolkitHost> = flow {
        val wslDistributions = scope.async { WslDistributionManager.getInstance().installedDistributions }
        val sshConfigs = scope.async { SshConfigManager.getInstance(project).configs }

        emit(ToolkitHost(LOCAL).also { host -> Log.info("emit host: $host") })
        if (WSLUtil.isSystemCompatible()) {
            wslDistributions.await().forEach {
                emit(ToolkitHost(WSL, it).also { host -> Log.info("emit host: $host") })
            }
        }

        if (PlatformUtils.isCommercialEdition()) {
            sshConfigs.await().forEach {
                emit(ToolkitHost(SSH, it).also { host -> Log.info("emit host: $host") })
            }
        }
    }

    private fun detectToolkitLocation(host: ToolkitHost): Flow<String> = flow {
        val process = probeXmakeLocCommand.let {
            when (host.type) {
                LOCAL -> (if (SystemInfo.isWindows) probeXmakeLocCommandOnWin else it).createLocalProcess()
                WSL -> it.createWslProcess(host.target as WSLDistribution)
                SSH -> it.createSshProcess(host.target as SshConfig)
            }
        }

        with(process.getBareExecutionResult()){
            Log.info("Host: ${host.type} ExitCode: $exitCode Output: ${stdOut.toString(Charsets.UTF_8)}")
            val paths = stdOut.toString(Charsets.UTF_8)
                .split(Regex("\\r\\n|\\n|\\r"))
                .filterNot { it.isBlank() || it.contains("not found") }
                .distinct()
            paths.forEach { emit(it); Log.info("emit path on ${host.type}: $it") }
        }
    }

    private fun detectToolkitVersion(host: ToolkitHost, path: String): Flow<String> = flow {
        val process = probeXmakeVersionCommand.withExePath(path).let {
            when (host.type) {
                LOCAL -> it.createLocalProcess()
                WSL -> it.createWslProcess(host.target as WSLDistribution)
                SSH -> it.createSshProcess(host.target as SshConfig)
            }
        }
        val (stdout, exitCode) = runProcess(process)
        val versionString = stdout.getOrElse { "" }.split(Regex(",")).first().split(" ").last()
        Log.info("ExitCode: $exitCode Version: $versionString")
        emit(versionString)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun detectXMakeToolkits(project: Project?) {
        detectionJob = scope.launch {
            val toolkitFlow = toolkitHostFlow(project)

            val pathFlow = toolkitFlow.flatMapMerge { host ->
                detectToolkitLocation(host).catch {
                    Log.warn(it.message)
                }.flowOn(Dispatchers.IO).buffer()
                    .distinctUntilChanged()
                    .filterNot { it.isBlank() }
                    .onEach { Log.info("output path: $it") }
                    .map { path -> host to path }
            }.flowOn(Dispatchers.Default).buffer()

            val versionFlow = pathFlow.flatMapMerge { (host, path) ->
                Log.info("detecting version: host: $host, path: $path")
                detectToolkitVersion(host, path).catch {
                    Log.warn(it.message)
                }.flowOn(Dispatchers.IO).buffer().filterNot { it.isBlank() }.map { versionString ->
                    when (host.type) {
                        LOCAL -> {
                            val name = SystemInfo.getOsName()
                            Toolkit(name, host, path, versionString)
                        }

                        WSL -> {
                            val wslDistribution = host.target as WSLDistribution
                            val name = wslDistribution.presentableName
                            Toolkit(name, host, path, versionString)
                        }

                        SSH -> {
                            val sshConfig = (host.target as SshConfig)
                            val name = sshConfig.presentableShortName
                            Toolkit(name, host, path, versionString)
                        }
                    }.apply { this.isRegistered = true; this.isValid = true }
                }
            }.flowOn(Dispatchers.Default).buffer()

            versionFlow.collect { toolkit ->
                // Todo: Consider cache
                fetchedToolkitsSet.add(toolkit)
                listenerList.forEach { listener ->
                    listener.onToolkitDetected(ToolkitDetectEvent(toolkit))
                }
                Log.info("toolkit added: $toolkit")
            }
            listenerList.forEach { it.onAllToolkitsDetected() }
        }
    }

    fun cancelDetection() {
        scope.launch {
            detectionJob.cancel()
        }
    }

    // Todo: Validate toolkit.
    fun validateXMakeToolkit() {
        scope.launch {
            try {
                validateJob = launch { validateToolkits() }
            } catch (e: Exception) {
                Log.error("Error: ${e.message}")
            }
        }
    }

    // Todo: Validate toolkit.
    fun validateToolkits(){

    }

    // Todo: Validate toolkit.
    fun cancelValidation(){

    }

    fun addToolkitDetectedListener(listener: ToolkitDetectedListener) {
        listenerList.add(listener)
    }

    override fun getState(): State {
        return storage
    }

    override fun loadState(state: State) {
        state.registeredToolkits.forEach {
            registerToolkit(it)
            fetchedToolkitsSet.add(it)
        }
        this.storage = state
    }

    fun loadToolkit(toolkit: Toolkit) {
        scope.launch(Dispatchers.IO) {
            toolkit.host.loadTarget()
            joinAll()
        }
    }

    fun registerToolkit(toolkit: Toolkit) {
        toolkit.isRegistered = true
        if (state.registeredToolkits.add(toolkit)){
            loadToolkit(toolkit)
        } else {
            loadToolkit(findRegisteredToolkitById(toolkit.id)!!)
        }
        Log.info("load registered toolkit: ${toolkit.name}, ${toolkit.id}")
    }

    // Todo: Increase robustness of this method
    fun unregisterToolkit(toolkit: Toolkit) {
        if(state.registeredToolkits.remove(toolkit)) {
            ProjectManager.getInstance().openProjects.forEach { project ->
                RunManager.getInstance(project).allConfigurationsList.forEach {
                    if (it is XMakeRunConfiguration) {
                        if (it.runToolkit?.id == toolkit.id)
                            it.runToolkit = null
                    }
                }
            }
        }
    }

    fun findRegisteredToolkitById(id: String): Toolkit? {
        return state.registeredToolkits.find { it.id == id }
    }

    fun getRegisteredToolkits(): List<Toolkit> {
        return state.registeredToolkits.filterNot { (it.host.type == SSH && PlatformUtils.isCommunityEdition()) }
    }

    companion object {
        fun  getInstance(): ToolkitManager = service()
        private val Log = logger<ToolkitManager>()
    }
}
