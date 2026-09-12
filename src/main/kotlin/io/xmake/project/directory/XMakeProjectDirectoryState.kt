package io.xmake.project.directory

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag

/** A project directory location specific to a non-local toolkit host. */
@Tag("hostDirectory")
data class HostDirectory(
    @Attribute("host")
    var hostId: String = "",
    @Attribute("directory")
    var directory: String = "",
)

/** The persisted project-directory schema. An empty [localDirectory] means "not configured":
 *  resolution then falls back to the IDE project root when it contains a root xmake.lua.
 *  Fields are mutable only because the platform serializer requires it; treat instances as
 *  read-only snapshots. */
data class XMakeProjectDirectoryState(
    @OptionTag(tag = "localDirectory", nameAttribute = "")
    var localDirectory: String = "",
    var hostDirectories: MutableList<HostDirectory> = mutableListOf(),
) {
    fun copyState(): XMakeProjectDirectoryState = copy(hostDirectories = hostDirectories.map(HostDirectory::copy).toMutableList())
}
