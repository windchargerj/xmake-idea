package io.xmake.project.directory

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.project.Project
import com.intellij.util.IncorrectOperationException
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHost
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.utils.SystemUtils
import io.xmake.utils.path.WorkingDirectoryResolver
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Computes the directory xmake should run in. The rules are deliberately asymmetric: an
 *  explicit host directory always wins, WSL may otherwise map the local directory into the
 *  distribution, and SSH cannot be verified locally so it requires explicit configuration. */
internal class XMakeProjectDirectoryResolver(
    private val project: Project,
    private val state: XMakeProjectDirectoryState,
) {

    /** Whether any directory source exists: host-specific entries (in memory), an IDE project
     *  root, or a configured local directory. Ordered cheap to expensive; [hasValidLocalDirectory]
     *  may touch the disk. The SystemUtils disjunct stays although [hasValidLocalDirectory]
     *  subsumes it for an empty local directory: the disjunct keeps counting the IDE project
     *  root as a source even when a configured local directory fails validation. */
    fun hasDirectorySource(): Boolean =
        state.hostDirectories.any { it.directory.isNotBlank() } ||
                SystemUtils.isXMakeProject(project) ||
                hasValidLocalDirectory()

    /** Whether [toolkit] has a usable project directory right now. Runs the full validation. */
    fun isResolved(toolkit: Toolkit): Boolean = resolves { resolveProjectDirectory(toolkit) }

    /** Resolves the directory to run xmake in for [toolkit]. */
    fun resolveProjectDirectory(toolkit: Toolkit): String {
        toolkit.requireUsable()
        return when (toolkit.host.type) {
            ToolkitHostType.LOCAL -> resolveLocalProjectDirectory()
            ToolkitHostType.WSL -> findExplicitHostDirectory(toolkit.host)
                ?: WorkingDirectoryResolver.resolve(project, resolveLocalProjectDirectory(), toolkit)
            ToolkitHostType.SSH -> findExplicitHostDirectory(toolkit.host)
                ?: throw RuntimeConfigurationError(
                    "XMake SSH project directory is not configured for ${toolkit.host.displayName}",
                )
        }
    }

    private fun Toolkit.requireUsable() {
        if (path.isBlank()) {
            throw RuntimeConfigurationError("XMake toolkit path is not set")
        }
        if (requiresBackend && !host.hasBackend) {
            throw RuntimeConfigurationError("XMake ${host.type} toolkit host is not available")
        }
    }

    /** Returns the local directory paired with a host-specific project location for file sync.
     *  Unlike [resolveLocalProjectDirectory] it does not require xmake.lua to exist yet, so it
     *  must not be merged with it: a freshly created project syncs before its first build. */
    fun resolveLocalSyncDirectory(): String {
        val configured = state.localDirectory
            .ifBlank { project.basePath }
            ?: throw RuntimeConfigurationError("XMake project directory is not configured")
        return try {
            WorkingDirectoryResolver.resolve(project, configured)
        } catch (error: IncorrectOperationException) {
            throw RuntimeConfigurationError(error.localizedMessage ?: "XMake project directory contains invalid macros")
        }
    }

    private fun resolveLocalProjectDirectory(): String {
        val configured = state.localDirectory
            .ifBlank { project.basePath?.takeIf(SystemUtils::hasRootXMakeLua) }
            ?: throw RuntimeConfigurationError("XMake project directory is not configured")
        return validateLocalDirectory(project, configured)
    }

    private fun findExplicitHostDirectory(host: ToolkitHost): String? =
        state.hostDirectories.firstOrNull { it.hostId == host.id.canonical }
            ?.directory
            ?.takeUnless(String::isBlank)
            ?.let(::validateAbsoluteHostDirectory)

    private fun hasValidLocalDirectory(): Boolean = resolves { resolveLocalProjectDirectory() }

    private inline fun resolves(block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: RuntimeConfigurationError) {
            false
        }
}

/** Validates a candidate local directory: macros resolve, the directory exists, and it
 *  contains a root xmake.lua. Returns the resolved absolute directory. */
internal fun validateAbsoluteHostDirectory(directory: String): String {
    if (!directory.startsWith('/')) {
        throw RuntimeConfigurationError("XMake project directory must be an absolute host path")
    }
    return directory
}

internal fun validateLocalDirectory(project: Project, directory: String): String {
    val resolved = try {
        WorkingDirectoryResolver.resolve(project, directory, validation = true)
    } catch (error: IncorrectOperationException) {
        throw RuntimeConfigurationError(error.localizedMessage ?: "XMake project directory contains invalid macros")
    }
    val path = try {
        Path.of(resolved)
    } catch (_: InvalidPathException) {
        throw RuntimeConfigurationError("XMake project directory is invalid: $resolved")
    }
    if (!Files.isDirectory(path)) {
        throw RuntimeConfigurationError("XMake project directory does not exist: $resolved")
    }
    if (!Files.isRegularFile(path.resolve("xmake.lua"))) {
        throw RuntimeConfigurationError("XMake project directory does not contain a root xmake.lua file: $resolved")
    }
    return resolved
}
