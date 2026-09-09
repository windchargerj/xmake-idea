package io.xmake.run.command

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.project.Project
import com.intellij.util.IncorrectOperationException
import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType
import io.xmake.utils.path.WorkingDirectoryResolver
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Resolves the optional cwd used by `xmake run -w` and direct debugger launches. */
internal fun resolveLaunchWorkingDirectory(
    project: Project,
    toolkit: Toolkit,
    workingDirectory: String,
): String? {
    if (workingDirectory.isBlank()) return null

    val resolvedDirectory = try {
        WorkingDirectoryResolver.resolve(project, workingDirectory, toolkit)
    } catch (error: IncorrectOperationException) {
        throw RuntimeConfigurationError(error.localizedMessage ?: "Launch working directory contains invalid macros")
    }

    if (toolkit.host.type != ToolkitHostType.LOCAL) {
        if (!resolvedDirectory.startsWith('/')) {
            throw RuntimeConfigurationError(
                "Launch working directory must be an absolute ${toolkit.host.type} host path",
            )
        }
        return resolvedDirectory
    }

    val path = try {
        Path.of(resolvedDirectory)
    } catch (_: InvalidPathException) {
        throw RuntimeConfigurationError("Launch working directory is invalid: $resolvedDirectory")
    }
    if (!Files.isDirectory(path)) {
        throw RuntimeConfigurationError("Launch working directory does not exist: $resolvedDirectory")
    }
    return resolvedDirectory
}
