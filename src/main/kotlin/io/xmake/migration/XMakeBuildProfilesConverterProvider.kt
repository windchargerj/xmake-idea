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

import com.intellij.conversion.ConversionContext
import com.intellij.conversion.ConversionProcessor
import com.intellij.conversion.ConverterProvider
import com.intellij.conversion.ProjectConverter
import com.intellij.conversion.RunManagerSettings
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.xmake.project.profile.XMakeBuildProfile
import io.xmake.project.profile.XMakeBuildProfileManager
import io.xmake.project.directory.LegacyProjectDirectory
import io.xmake.project.directory.XMakeProjectDirectoryState
import io.xmake.project.directory.migrateLegacyDirectories
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrates the build settings stored in old XMake run configurations into project build
 * profiles. Runs once before the project loads: it rewrites the run configuration files
 * and merges the imported profiles into xmake.xml.
 */
class XMakeBuildProfilesConverterProvider : ConverterProvider() {
    override fun getConversionDescription(): String =
        "XMake build settings were moved from run configurations to project build profiles"

    override fun createConverter(context: ConversionContext): ProjectConverter =
        XMakeBuildProfilesConverter(context)
}

private class XMakeBuildProfilesConverter(
    private val context: ConversionContext,
) : ProjectConverter() {

    private val importedProfiles = mutableListOf<XMakeBuildProfile>()
    private val importedRootDirectories = mutableListOf<LegacyProjectDirectory>()

    override fun createRunConfigurationsConverter(): ConversionProcessor<RunManagerSettings> =
        object : ConversionProcessor<RunManagerSettings>() {
            override fun isConversionNeeded(settings: RunManagerSettings): Boolean =
                settings.xmakeRunConfigurations().any { element -> hasLegacyBuildSettings(element) }

            override fun process(settings: RunManagerSettings) {
                settings.xmakeRunConfigurations().forEach { configuration ->
                    val name = configuration.getAttributeValue("name").orEmpty()
                    readLegacyBuildSettingsAsProfile(configuration, name)?.let { migrated ->
                        importedProfiles += migrated.profile
                        migrated.legacyProjectDirectory?.let(importedRootDirectories::add)
                        writeBuildProfileReference(configuration, migrated.profile.id)
                        removeLegacyBuildSettings(configuration)
                    }
                }
            }
        }

    override fun getAdditionalAffectedFiles(): Collection<Path> {
        return if (Files.exists(profilesFile)) listOf(profilesFile) else emptyList()
    }

    override fun postProcessingFinished() {
        if (importedProfiles.isEmpty() && importedRootDirectories.isEmpty()) return
        XMakeBuildProfileFile(profilesFile)
            .mergeImportedProfiles(importedProfiles, importedRootDirectories)
    }

    private val profilesFile: Path
        get() = context.settingsBaseDir?.resolve(PROFILES_FILE)
            ?: error("Cannot locate the project settings directory")

    private companion object {
        const val PROFILES_FILE = "xmake.xml"
    }
}

private fun RunManagerSettings.xmakeRunConfigurations(): List<Element> =
    runConfigurations.filter { element -> element.getAttributeValue("type") == XMAKE_CONFIGURATION_TYPE }

private const val XMAKE_CONFIGURATION_TYPE = "XMakeRunConfiguration"

private const val PROFILE_ELEMENT_TAG = "XMakeBuildProfile"

private fun profileElements(element: Element): List<Element> =
    element.children.flatMap { child ->
        if (child.name == PROFILE_ELEMENT_TAG) {
            listOf(child)
        } else {
            profileElements(child)
        }
    }

private fun optionValue(profile: Element, name: String): String? =
    profile.getChildren("option")
        .firstOrNull { option -> option.getAttributeValue("name") == name }
        ?.let { it.getAttributeValue("value") ?: it.text }

/** Reads, merges, and writes project build-profile and project-root storage. */
private class XMakeBuildProfileFile(private val path: Path) {

    fun mergeImportedProfiles(
        importedProfiles: List<XMakeBuildProfile>,
        importedRootDirectories: List<LegacyProjectDirectory>,
    ) {
        val root = loadOrCreate()
        val profilesComponent = findOrCreateComponent(root, PROFILES_COMPONENT)
        val mergedProfiles = mergeProfiles(existingProfiles(profilesComponent), importedProfiles)
        writeProfiles(profilesComponent, mergedProfiles)
        if (importedRootDirectories.isNotEmpty()) {
            writeImportedRoots(root, importedRootDirectories)
        }
        JDOMUtil.write(root, path)
    }

    private fun loadOrCreate(): Element =
        if (Files.exists(path)) {
            JDOMUtil.load(path)
        } else {
            Element("project").setAttribute("version", "4")
        }

    private fun findOrCreateComponent(root: Element, name: String): Element =
        root.getChildren("component")
            .firstOrNull { element -> element.getAttributeValue("name") == name }
            ?: Element("component").setAttribute("name", name).also(root::addContent)

    private fun existingProfiles(component: Element): List<XMakeBuildProfile> {
        return XMakeBuildProfileManager.State.fromElement(component).toProfiles()
    }

    private fun mergeProfiles(
        existing: List<XMakeBuildProfile>,
        imported: List<XMakeBuildProfile>,
    ): List<XMakeBuildProfile> {
        val result = existing.toMutableList()
        // Use the same effective order and naming rules as the project service. Keep the raw
        // existing records in the file; they may still be referenced by an older run config.
        val normalizedExisting = XMakeBuildProfile.normalize(existing)
        val knownIds = normalizedExisting.mapTo(mutableSetOf(), XMakeBuildProfile::id)
        val knownNames = normalizedExisting.mapTo(mutableSetOf()) { profile -> profile.name }
        imported.forEach { profile ->
            if (!XMakeBuildProfile.isValidId(profile.id) || !knownIds.add(profile.id)) return@forEach
            val unique = profile.copy(name = XMakeBuildProfile.uniqueName(profile.name, knownNames))
            knownNames += unique.name
            result += unique
        }
        return result
    }

    private fun writeProfiles(component: Element, profiles: List<XMakeBuildProfile>) {
        // Keep per-profile legacy directories as workingDirectory options: the runtime
        // loadState rewrites and migrates them into the project directory on next open.
        val legacyDirectoriesById = profileElements(component).mapNotNull { profile ->
            val id = optionValue(profile, "id") ?: return@mapNotNull null
            optionValue(profile, "workingDirectory")?.takeIf(String::isNotBlank)?.let { id to it }
        }.toMap()

        component.removeContent()
        val state = XMakeBuildProfileManager.State.fromProfiles(profiles)
        XmlSerializer.serializeInto(state, component)

        legacyDirectoriesById.forEach { (id, directory) ->
            profileElements(component)
                .firstOrNull { profile -> optionValue(profile, "id") == id }
                ?.addContent(Element("option").setAttribute("name", "workingDirectory").setAttribute("value", directory))
        }
    }

    private fun writeImportedRoots(
        root: Element,
        imported: List<LegacyProjectDirectory>,
    ) {
        val component = findOrCreateComponent(root, DIRECTORY_COMPONENT)
        val state = XMakeProjectDirectoryState()
        XmlSerializer.deserializeInto(state, component)

        val migrated = state.migrateLegacyDirectories(imported)
        if (migrated == state) return

        component.removeContent()
        XmlSerializer.serializeInto(migrated, component)
    }

    private companion object {
        const val PROFILES_COMPONENT = "XMakeBuildProfiles"
        const val DIRECTORY_COMPONENT = "XMakeProjectDirectory"
    }
}
