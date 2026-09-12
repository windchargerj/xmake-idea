package io.xmake.project.directory

import io.xmake.project.toolkit.Toolkit
import io.xmake.project.toolkit.ToolkitHostType

/** A project directory that was persisted by an older build-profile-based version. */
data class LegacyProjectDirectory(val toolkit: Toolkit?, val directory: String) {
    val hostType: ToolkitHostType
        get() = toolkit?.host?.type ?: ToolkitHostType.LOCAL
}

/** Imports directories previously owned by build profiles. First-wins: migration never
 *  overwrites a directory configured after the upgrade. */
internal fun XMakeProjectDirectoryState.migrateLegacyDirectories(directories: List<LegacyProjectDirectory>): XMakeProjectDirectoryState =
    directories.fold(this) { state, legacy -> state.importLegacyDirectory(legacy) }

/** A convertible WSL directory is the local tree seen through the distribution: fold it into
 *  the local directory, else keep it host-specific. */
private fun XMakeProjectDirectoryState.importLegacyDirectory(legacy: LegacyProjectDirectory): XMakeProjectDirectoryState = when (legacy.hostType) {
    ToolkitHostType.LOCAL -> importLocalDirectory(legacy.directory)
    ToolkitHostType.WSL -> importWslDirectory(legacy)
    ToolkitHostType.SSH -> importHostDirectory(legacy, legacy.directory)
}

private fun XMakeProjectDirectoryState.importWslDirectory(legacy: LegacyProjectDirectory): XMakeProjectDirectoryState {
    val windowsDirectory = legacy.toolkit
        ?.host
        ?.wslDistribution
        ?.takeIf { legacy.directory.startsWith('/') }
        ?.getWindowsPath(legacy.directory)
    if (windowsDirectory != null) {
        return importLocalDirectory(windowsDirectory)
    }
    return importHostDirectory(legacy, legacy.directory)
}

private fun XMakeProjectDirectoryState.importLocalDirectory(directory: String): XMakeProjectDirectoryState =
    if (localDirectory.isBlank()) copy(localDirectory = directory) else this

private fun XMakeProjectDirectoryState.importHostDirectory(legacy: LegacyProjectDirectory, directory: String): XMakeProjectDirectoryState {
    val hostId = legacy.toolkit?.host?.id?.canonical ?: return this
    if (hostDirectories.any { it.hostId == hostId }) return this
    return copy(hostDirectories = (hostDirectories + HostDirectory(hostId, directory)).toMutableList())
}
