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
package io.xmake.migration

import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.annotations.OptionTag
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.toolkit.Toolkit
import org.jdom.Element
import java.util.UUID

/** Maps the build settings stored in old run configurations to a project build profile. */
internal fun readLegacyBuildProfile(element: Element, configurationName: String): XMakeBuildProfile? {
    if (!hasLegacyBuildProfileFields(element)) return null

    val legacy = LegacyBuildProfileState().also { state -> XmlSerializer.deserializeInto(state, element) }
    return XMakeBuildProfile(
        id = legacyProfileId(configurationName, legacy),
        name = configurationName,
        toolkitId = legacy.toolkit?.id,
        platform = legacy.platform,
        architecture = legacy.architecture,
        toolchain = legacy.toolchain,
        mode = legacy.mode,
        workingDirectory = legacy.workingDirectory,
        buildDirectory = legacy.buildDirectory,
        androidNdkDirectory = legacy.androidNdkDirectory,
        verbose = legacy.enableVerbose,
        additionalConfiguration = legacy.additionalConfiguration,
    )
}

internal fun hasLegacyBuildProfileFields(element: Element): Boolean =
    LEGACY_PROFILE_TAGS.any { tag -> element.getChild(tag) != null }

internal fun removeLegacyBuildProfileFields(element: Element) {
    LEGACY_PROFILE_TAGS.forEach(element::removeChildren)
}

/**
 * The same legacy run configuration must always map to the same profile ID: the project
 * converter and the transitional runtime fallback can then run in any order, any number
 * of times, without creating duplicate profiles.
 */
private fun legacyProfileId(configurationName: String, legacy: LegacyBuildProfileState): String =
    UUID.nameUUIDFromBytes(
        listOf(
            configurationName,
            legacy.toolkit?.id.orEmpty(),
            legacy.platform,
            legacy.architecture,
            legacy.toolchain,
            legacy.mode,
            legacy.workingDirectory,
            legacy.buildDirectory,
            legacy.androidNdkDirectory,
            legacy.enableVerbose.toString(),
            legacy.additionalConfiguration,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    ).toString()

// Tag names intentionally match the legacy run configuration XML format.
private class LegacyBuildProfileState {
    @OptionTag(tag = "activatedToolkit")
    var toolkit: Toolkit? = null

    @OptionTag(tag = "platform")
    var platform: String = "default"

    @OptionTag(tag = "architecture")
    var architecture: String = "default"

    @OptionTag(tag = "toolchain")
    var toolchain: String = "default"

    @OptionTag(tag = "mode")
    var mode: String = "release"

    @OptionTag(tag = "workingDirectory")
    var workingDirectory: String = ""

    @OptionTag(tag = "buildDirectory")
    var buildDirectory: String = ""

    @OptionTag(tag = "androidNDKDirectory")
    var androidNdkDirectory: String = ""

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
