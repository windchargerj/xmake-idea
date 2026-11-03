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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.run.pack

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import io.xmake.project.directory.ui.DirectoryBrowser
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.ui.coroutineScope
import io.xmake.utils.ui.uiEdt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/** The package combo entry meaning "no explicit xpack": `xmake pack` packs every declared one. */
private const val ALL_PACKAGES = "<all>"

/** Why the formats checkboxes only offer a subset of what xmake can generate. */
private const val FORMATS_COMMENT =
    "Formats filter what the xpack already declares in set_formats - " +
            "undeclared formats are silently skipped by xmake"

/** The pack run's own options: which xpack, its formats, and where the artifacts land. */
internal class XMakePackRunConfigurationEditor : SettingsEditor<XMakePackRunConfiguration>() {

    private val packName = ComboBox<String>().apply { isEditable = true }
    private val formatsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
    private val outputDirectory = DirectoryBrowser(
        null,
        "Select output directory",
        "Select the pack output directory",
    )
    private val basename = JBTextField()
    private val autoBuild = JBCheckBox("Build the targets before packing (--autobuild)")
    private val extraArguments = RawCommandLineEditor()

    /** The xpack() entries the enumeration query last answered; empty until it succeeds. */
    private var entries: List<XMakePackEntry> = emptyList()

    /** Rewrites the formats row's comment; wired to the real line in [createEditor]. */
    private var formatsComment: (String) -> Unit = {}

    /** The formats checked in the row. Kept across package re-selections and round-trips
     *  for the formats the packages share - but pruned to whatever the selected package
     *  declares ([refreshFormatsRow]), because a format no checkbox shows anymore must not
     *  ride into the run's `-f` invisibly. */
    private val selectedFormats = LinkedHashSet<String>()

    /** The project behind the configuration being edited; a query before any reset has
     *  nothing to run against and stays silent. */
    private var editedProject: Project? = null

    private var isResetting = false

    /** The enumeration runs an xmake query, so it starts off the EDT like the test editor's
     *  listing does: one cancellable job at a time, landing on the EDT under any open modal
     *  dialog - this editor lives inside one. */
    private val queryScope = coroutineScope()
    private var queryJob: Job? = null

    init {
        packName.addActionListener {
            if (!isResetting) refreshFormatsRow()
        }
    }

    override fun resetEditorFrom(configuration: XMakePackRunConfiguration) {
        editedProject = configuration.project
        isResetting = true
        try {
            packName.editor.item = configuration.packName.trim().ifEmpty { ALL_PACKAGES }
            selectedFormats.clear()
            selectedFormats += configuration.selectedFormats()
            outputDirectory.text = configuration.outputDirectory
            basename.text = configuration.basename
            autoBuild.isSelected = configuration.autoBuild
            extraArguments.text = configuration.extraArguments
            refreshFormatsRow()
        } finally {
            isResetting = false
        }
        refreshOutputDirectoryToolkit()
        scheduleEnumeration()
    }

    override fun applyEditorTo(configuration: XMakePackRunConfiguration) {
        configuration.packName = packNameValue()
        configuration.packFormats = selectedFormats.joinToString(",")
        configuration.outputDirectory = outputDirectory.text.trim()
        configuration.basename = basename.text.trim()
        configuration.autoBuild = autoBuild.isSelected
        configuration.extraArguments = extraArguments.text
    }

    override fun createEditor(): JComponent = panel {
        row("Package:") {
            cell(packName).align(AlignX.FILL)
                .comment("The xpack() to pack; $ALL_PACKAGES packs every declared one")
            button("Refresh") { scheduleEnumeration(force = true) }
                .component.toolTipText = "Re-run the xpack enumeration query"
        }
        row("Formats:") {
            formatsComment = cell(formatsRow).align(AlignX.FILL).mutableComment(FORMATS_COMMENT)
        }
        row("Output directory:") {
            cell(outputDirectory).align(AlignX.FILL)
                .comment("Empty uses xmake's default, <build dir>/xpack/<name>")
        }
        row("Basename:") {
            cell(basename).align(AlignX.FILL)
                .comment("Empty uses xmake's default (the xpack name, with its -src/-version variants)")
        }
        row {
            cell(autoBuild)
        }
        row("Extra arguments:") {
            cell(extraArguments).align(AlignX.FILL)
        }
    }

    /** The package name the configuration gets: the `<all>` sentinel collapses to empty. */
    private fun packNameValue(): String {
        val text = (packName.editor.item as? String)?.trim().orEmpty()
        return if (text == ALL_PACKAGES) "" else text
    }

    private fun currentEntry(): XMakePackEntry? =
        entries.firstOrNull { it.name == packNameValue() }

    /** Rebuilds the checkbox row from the selected package's declared formats; a package
     *  without declarations, or `<all>`, offers none - the run then packs every declared
     *  format of every xpack it touches. Free text is deliberately not offered: `-f` cannot
     *  add formats, so an undeclared one would only ever be skipped (docs/roadmap-designs/
     *  packaging.md, F7).
     *
     *  On a package with declared formats the checked set is pruned to them: a format the
     *  new package does not declare has no checkbox to uncheck, and keeping it selected
     *  would send an invisible `-f` that xmake answers with nothing packed. Formats the
     *  packages share keep their check - that is what the kept-across-selections set is
     *  for. `<all>` prunes nothing (no package to measure against) but the hint names what
     *  still filters, so the residue is visible instead of smuggled into the command line. */
    private fun refreshFormatsRow() {
        formatsRow.removeAll()
        val entry = currentEntry()
        val declared = entry?.formats.orEmpty()
        if (entry != null) {
            selectedFormats.retainAll(declared.toSet())
        }
        declared.forEach { format ->
            formatsRow.add(JBCheckBox(format, format in selectedFormats).apply {
                addActionListener {
                    if (isSelected) selectedFormats += format else selectedFormats -= format
                }
            })
        }
        setFormatsHint(
            when {
                declared.isNotEmpty() -> FORMATS_COMMENT
                // `<all>` or a package without declarations, with formats still checked from a
                // previous selection: say so, or the run would filter every xpack invisibly.
                selectedFormats.isNotEmpty() ->
                    "Still filtering by ${selectedFormats.joinToString(", ")} - the xpacks that do not " +
                            "declare them pack nothing; select a package that declares one to uncheck it"
                else ->
                    "Pick a package to see the formats it declares; leaving all unchecked packs every declared format"
            },
        )
        formatsRow.revalidate()
        formatsRow.repaint()
    }

    /** Pushes the profile's resolved toolkit to the browse button, which already knows how
     *  to browse LOCAL paths, WSL distributions, and SSH hosts. */
    private fun refreshOutputDirectoryToolkit() {
        val project = editedProject?.takeUnless { it.isDisposed }
        val toolkit = project?.activeOrSingleXMakeBuildProfile?.resolveToolkit(project)
        outputDirectory.setToolkit(toolkit)
    }

    /** Lists the project's xpack() packages with the shipped script and fills the pickers.
     *  A failed query leaves the fields editable - the run can still be launched, and xmake
     *  reports the same problem in the console.
     *
     *  Answers already in [ENUMERATION_CACHE] fill the pickers without the query: the
     *  settings editor is rebuilt for every dialog open and every re-selection in the
     *  tree, and each of those would otherwise put a fresh `xpacks.lua` run behind
     *  whatever occupies the executor's single lane. The Refresh button passes
     *  [force] to re-query and overwrite. */
    private fun scheduleEnumeration(force: Boolean = false) {
        val project = editedProject ?: return
        if (project.isDisposed) return
        val profile = project.activeOrSingleXMakeBuildProfile
        if (profile == null) {
            setFormatsHint("$SELECT_BUILD_PROFILE_FIRST to enumerate the project's xpack() packages")
            return
        }
        if (!force) {
            ENUMERATION_CACHE[profile.id]?.let { cached ->
                applyEntries(cached)
                return
            }
        }
        queryJob?.cancel()
        // The query may queue behind a build on the executor's lane, so say it is running
        // before submitting - otherwise the stale hint reads as if nothing were loading.
        setFormatsHint("Listing the project's xpack() packages...")
        queryJob = queryScope.launch {
            val output = try {
                project.xmakeExecutor.submit {
                    val query = project.xmakeCommands(profile).createXpackQuery()
                    project.xmakeExecutor.captureStandardOutput(query)
                }.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                withContext(uiEdt) {
                    // The same failure sentence shape the Packages panel's first load uses.
                    setFormatsHint(
                        "Could not list the project's xpack() packages: " +
                                (error.message ?: error.javaClass.simpleName) +
                                " - fix the problem and press Refresh",
                    )
                }
                return@launch
            }
            val parsed = XMakePackEnumeration.parse(output)
            ENUMERATION_CACHE[profile.id] = parsed
            withContext(uiEdt) {
                applyEntries(parsed)
            }
        }
    }

    /** Fills the pickers from one enumeration answer; on the EDT like every other write. */
    private fun applyEntries(parsed: List<XMakePackEntry>) {
        entries = parsed
        repopulatePackNames()
        refreshFormatsRow()
        if (parsed.isEmpty()) {
            setFormatsHint(
                "No xpack() packages found - add includes(\"@builtin/xpack\") " +
                        "and an xpack() section to the project",
            )
        }
    }

    private fun repopulatePackNames() {
        val previous = (packName.editor.item as? String)?.trim().orEmpty()
        packName.model = DefaultComboBoxModel(
            (listOf(ALL_PACKAGES) + entries.map(XMakePackEntry::name)).toTypedArray(),
        )
        packName.editor.item = previous.ifEmpty { ALL_PACKAGES }
    }

    private fun setFormatsHint(text: String) {
        formatsComment(text)
    }

    private companion object {

        /**
         * The enumeration answers per profile id, shared between editor instances: profile
         * ids are per-project UUIDs, so the key cannot cross projects, and the Refresh
         * button is the way to see a project whose xpack() sections changed. Caching here
         * is what keeps dialog opens and configuration re-selections off the executor lane.
         */
        private val ENUMERATION_CACHE = java.util.concurrent.ConcurrentHashMap<String, List<XMakePackEntry>>()
    }
}

/** Adds a comment line under a cell whose text the editor can rewrite later. Kept as a
 *  file-local copy of the run configuration editor's helper so both settings editors
 *  speak the same Cell.comment mechanism without touching shared UI utils. */
private fun Cell<*>.mutableComment(text: String): (String) -> Unit {
    comment(text)
    val line = comment!!
    return { value ->
        line.text = value
        line.parent?.revalidate()
    }
}
