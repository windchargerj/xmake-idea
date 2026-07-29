/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (C) 2015-present, Xmake Open Source Community.
 */
package io.xmake.run

import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.OptionTag
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.toolkit.Toolkit
import org.jdom.Element
import java.util.UUID

internal fun readLegacyBuildProfile(element: Element, configurationName: String): XMakeBuildProfile? {
    if (LEGACY_PROFILE_TAGS.none { element.getChild(it) != null }) return null

    val legacy = LegacyBuildProfileState()
    XmlSerializer.deserializeInto(legacy, element)
    val identity = listOf(
        configurationName,
        legacy.runToolkit?.id.orEmpty(),
        legacy.runPlatform,
        legacy.runArchitecture,
        legacy.runToolchain,
        legacy.runMode,
        legacy.runWorkingDir,
        legacy.buildDirectory,
        legacy.androidNDKDirectory,
        legacy.enableVerbose.toString(),
        legacy.additionalConfiguration,
    ).joinToString("\u0000")
    return XMakeBuildProfile(
        id = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(),
        name = configurationName,
        toolkitId = legacy.runToolkit?.id,
        platform = legacy.runPlatform,
        architecture = legacy.runArchitecture,
        toolchain = legacy.runToolchain,
        mode = legacy.runMode,
        workingDirectory = legacy.runWorkingDir,
        buildDirectory = legacy.buildDirectory,
        androidNdkDirectory = legacy.androidNDKDirectory,
        verbose = legacy.enableVerbose,
        additionalConfiguration = legacy.additionalConfiguration,
    )
}

internal fun removeLegacyBuildProfileFields(element: Element) {
    LEGACY_PROFILE_TAGS.forEach(element::removeChildren)
}

// These property names intentionally match the legacy XMLB bean as well as its tag names.
private class LegacyBuildProfileState {
    @OptionTag(tag = "activatedToolkit")
    var runToolkit: Toolkit? = null

    @OptionTag(tag = "platform")
    var runPlatform: String = "default"

    @OptionTag(tag = "architecture")
    var runArchitecture: String = "default"

    @OptionTag(tag = "toolchain")
    var runToolchain: String = "default"

    @OptionTag(tag = "mode")
    var runMode: String = "release"

    @OptionTag(tag = "workingDirectory")
    var runWorkingDir: String = ""

    @OptionTag(tag = "buildDirectory")
    var buildDirectory: String = ""

    @OptionTag(tag = "androidNDKDirectory")
    var androidNDKDirectory: String = ""

    @OptionTag(tag = "enableVerbose")
    var enableVerbose: Boolean = false

    @OptionTag(tag = "additionalConfiguration")
    var additionalConfiguration: String = ""
}

private val LEGACY_PROFILE_TAGS = setOf(
    "activatedToolkit",
    "platform",
    "architecture",
    "toolchain",
    "mode",
    "workingDirectory",
    "buildDirectory",
    "androidNDKDirectory",
    "enableVerbose",
    "additionalConfiguration",
)
