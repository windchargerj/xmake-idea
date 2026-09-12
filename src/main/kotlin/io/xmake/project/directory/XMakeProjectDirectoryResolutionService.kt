package io.xmake.project.directory

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the answer to "is this project an XMake project right now" cached, so action updates
 *  on the EDT never touch the disk. The answer is recomputed when the configured directories
 *  change or an xmake.lua is created, deleted, moved, or renamed directly. Moving or deleting
 *  an enclosing directory is not detected. */
@Service(Service.Level.PROJECT)
class XMakeProjectDirectoryResolutionService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    @Volatile
    var hasDirectorySource: Boolean = false
        private set

    private val requestGeneration = AtomicInteger()
    private val messageBusConnection = project.messageBus.connect(this)

    init {
        resolveAsync()
        messageBusConnection.subscribe(
            XMakeProjectDirectoryManager.TOPIC,
            XMakeProjectDirectoryManager.Listener { resolveAsync() },
        )
        messageBusConnection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any(::isRelevantVfsEvent)) resolveAsync()
            }
        })
    }

    private fun resolveAsync() {
        if (project.isDisposed) return
        val request = requestGeneration.incrementAndGet()
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) { resolve() }
            if (!project.isDisposed && request == requestGeneration.get()) {
                hasDirectorySource = refreshed
            }
        }
    }

    private fun resolve(): Boolean = project.xmakeProjectDirectories.hasDirectorySource()

    // Deliberately over-inclusive: an xmake.lua outside the project may back a configured
    // root, so filtering by project membership would miss relevant changes.
    private fun isRelevantVfsEvent(event: VFileEvent): Boolean =
        when (event) {
            is VFileCreateEvent, is VFileDeleteEvent, is VFileMoveEvent ->
                event.file?.name?.equals("xmake.lua", ignoreCase = true) == true

            is VFilePropertyChangeEvent ->
                event.propertyName == VirtualFile.PROP_NAME &&
                        listOf(event.oldValue, event.newValue).any { value ->
                            value is String && value.equals("xmake.lua", ignoreCase = true)
                        }

            else -> false
        }

    override fun dispose() {
        // The message bus connection registered with [this] is disposed automatically.
    }
}

/** Whether the project currently resolves to an XMake project directory. The answer is cached
 *  by [XMakeProjectDirectoryResolutionService] and safe to read from any thread. */
val Project.hasResolvedXMakeProjectDirectory: Boolean
    get() = getService(XMakeProjectDirectoryResolutionService::class.java)?.hasDirectorySource == true

/** Warms the cached answer at startup so the first menu render does not run a cold resolve. */
class XMakeProjectDirectoryResolutionActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(XMakeProjectDirectoryResolutionService::class.java)
    }
}
