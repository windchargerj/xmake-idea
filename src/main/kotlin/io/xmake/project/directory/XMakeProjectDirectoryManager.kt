package io.xmake.project.directory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.xmake.project.toolkit.Toolkit

/** Owns the XMake project directory independently from individual build profiles. */
@Service(Service.Level.PROJECT)
@State(name = "XMakeProjectDirectory", storages = [Storage("xmake.xml")])
class XMakeProjectDirectoryManager(private val project: Project) :
    PersistentStateComponent<XMakeProjectDirectoryState> {

    private val stateLock = Any()
    private var currentState = XMakeProjectDirectoryState()

    /** The current state. Writes swap in freshly built instances and nothing mutates a State
     *  afterwards, so handing it out without copying is safe. */
    private val current: XMakeProjectDirectoryState
        get() = synchronized(stateLock) { currentState }

    override fun getState(): XMakeProjectDirectoryState = current.copyState()

    override fun loadState(state: XMakeProjectDirectoryState) {
        updateState(state)
    }

    /** Replaces the state from the settings page or the project wizard. */
    fun replaceState(state: XMakeProjectDirectoryState) {
        updateState(state)
    }

    private fun updateState(replacement: XMakeProjectDirectoryState) {
        val normalized = normalize(replacement)
        val changed = synchronized(stateLock) {
            if (currentState == normalized) false else {
                currentState = normalized
                true
            }
        }
        if (changed) publishDirectoryChanged()
    }

    private fun normalize(state: XMakeProjectDirectoryState): XMakeProjectDirectoryState = XMakeProjectDirectoryState(
        localDirectory = state.localDirectory.trim(),
        hostDirectories = state.hostDirectories
            .map { it.copy(hostId = it.hostId.trim(), directory = it.directory.trim()) }
            .filter { it.hostId.isNotEmpty() && it.directory.isNotEmpty() }
            .distinctBy { it.hostId }
            .toMutableList(),
    )

    private fun publishDirectoryChanged() {
        val publish = Runnable {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(TOPIC).projectDirectoryChanged()
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            publish.run()
        } else {
            application.invokeLater(publish)
        }
    }

    /** Whether any directory source exists: host-specific entries (in memory), an IDE project
     *  root, or a configured local directory. */
    /** Delegates to [XMakeProjectDirectoryResolver.hasDirectorySource]. */
    fun hasDirectorySource(): Boolean = resolver().hasDirectorySource()

    /** Delegates to [XMakeProjectDirectoryResolver.isResolved]. */
    fun isResolved(toolkit: Toolkit): Boolean = resolver().isResolved(toolkit)

    /** Delegates to [XMakeProjectDirectoryResolver.resolveProjectDirectory]. */
    fun resolveProjectDirectory(toolkit: Toolkit): String = resolver().resolveProjectDirectory(toolkit)

    /** Delegates to [XMakeProjectDirectoryResolver.resolveLocalSyncDirectory]. */
    fun resolveLocalSyncDirectory(): String = resolver().resolveLocalSyncDirectory()

    private fun resolver() = XMakeProjectDirectoryResolver(project, current)

    /** Imports directories previously owned by build profiles. First-wins: migration never
     *  overwrites a directory configured after the upgrade. */
    fun migrateLegacyProjectDirectories(directories: List<LegacyProjectDirectory>) {
        updateState(current.migrateLegacyDirectories(directories))
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("XMake project directory changed", Listener::class.java)

        fun getInstance(project: Project): XMakeProjectDirectoryManager =
            project.getService(XMakeProjectDirectoryManager::class.java)
                ?: error("Failed to get XMakeProjectDirectoryManager for $project")
    }

    fun interface Listener {
        fun projectDirectoryChanged()
    }
}

val Project.xmakeProjectDirectories: XMakeProjectDirectoryManager
    get() = XMakeProjectDirectoryManager.getInstance(this)
