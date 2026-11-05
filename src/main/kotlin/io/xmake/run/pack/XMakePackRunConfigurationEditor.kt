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
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

/** The package combo entry meaning "no explicit xpack": `xmake pack` packs every declared one. */
private const val ALL_PACKAGES = "<all>"

/** Why the formats checkboxes only offer a subset of what xmake can generate. */
private const val FORMATS_COMMENT =
    "Formats filter what the xpack already declares in set_formats - " +
            "undeclared formats are silently skipped by xmake"

/** The package row's standing line: what the field takes besides an enumerated xpack name. */
private const val PACKAGE_COMMENT =
    "The xpack() to pack; $ALL_PACKAGES packs every declared one"

/** The package row's answer when the enumeration succeeded but found nothing to list. */
private const val EMPTY_RESULT_HINT =
    "No xpack() packages found - add includes(\"@builtin/xpack\") " +
            "and an xpack() section to the project"

/** Appended when the answer came from the enumeration cache: the cache cannot see xpack()
 *  sections added after it was filled, so the staleness is named on the row instead of
 *  leaving the Refresh button as the only way to find out. */
private const val CACHED_SUFFIX = " (cached - press Refresh to re-scan)"

/** The package row's sentence while the enumeration query is in flight. */
private const val LISTING_HINT = "Listing the project's xpack() packages..."

/** The Refresh button's caption, idle and waiting for a press. */
private const val REFRESH = "Refresh"

/** The Refresh button's caption while the enumeration query is in flight: the query may
 *  queue behind a build on the executor's single lane, so a disabled button that says so
 *  is what keeps "queued" from reading as "hung". */
private const val REFRESH_LISTING = "Listing..."

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

    /** Rewrites the package row's comment; wired to the real line in [createEditor]. The
     *  enumeration's states live here, not on the formats row: they describe how this
     *  row's list was filled, and this is the row the user watches for the answer. */
    private var packageComment: (String) -> Unit = {}

    /** Rewrites the formats row's comment; wired to the real line in [createEditor]. */
    private var formatsComment: (String) -> Unit = {}

    /** The Refresh button, wired to the real row in [createEditor]; the enumeration flips
     *  it to its in-flight caption while its query waits on the executor's lane. */
    private var refreshButton: JButton? = null

    /** The process sentence - listing, failure, no profile - the enumeration last put on
     *  the package row. While it stands, name edits keep it verbatim: the unknown-name and
     *  cached-answer call-outs only compose onto an answer the enumeration has given. */
    private var processHint: String? = null

    /** Whether the entries on hand came from the enumeration cache, which cannot see
     *  xpack() sections added after it was filled - the package row says so instead of
     *  leaving the Refresh button as the only reminder. */
    private var answeredFromCache = false

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
            if (!isResetting) {
                refreshFormatsRow()
                refreshPackageHint()
            }
        }
        // Free text only fires the action listener on Enter; listening to the editor's
        // document keeps the unknown-name call-out live while the name is being typed.
        (packName.editor.editorComponent as? JTextField)?.document?.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (!isResetting) refreshPackageHint()
                }
            },
        )
    }

    override fun resetEditorFrom(configuration: XMakePackRunConfiguration) {
        editedProject = configuration.project
        // The browser is built before any project is known; pointing it at the edited one
        // here gives the browse listener its project anchor before the toolkit resolves.
        outputDirectory.project = editedProject
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
            packageComment = cell(packName).align(AlignX.FILL).mutableComment(PACKAGE_COMMENT)
            refreshButton = button(REFRESH) { scheduleEnumeration(force = true) }.component
                .apply { toolTipText = "Re-run the xpack enumeration query" }
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
        if (declared.isEmpty()) {
            // With nothing to check the row would collapse to a zero-height empty panel,
            // leaving the "Formats:" label dangling over nothing; the disabled placeholder
            // keeps the row - and its "none declared" state - visible on the row itself.
            formatsRow.add(JBLabel("none declared").apply { isEnabled = false })
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
            setProcessHint("$SELECT_BUILD_PROFILE_FIRST to enumerate the project's xpack() packages")
            return
        }
        if (!force) {
            ENUMERATION_CACHE[profile.id]?.let { cached ->
                answeredFromCache = true
                applyEntries(cached)
                return
            }
        }
        queryJob?.cancel()
        answeredFromCache = false
        // The query may queue behind a build on the executor's lane, so say it is running
        // before submitting - the row's sentence and the disabled, renamed Refresh button
        // together keep "queued" from reading as "hung".
        setProcessHint(LISTING_HINT)
        setQueryInFlight(true)
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
                    setQueryInFlight(false)
                    // The same failure sentence shape the Packages panel's first load uses.
                    setProcessHint(
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

    /** Fills the pickers from one enumeration answer; on the EDT like every other write.
     *  The answer retires the process sentence - the package row switches to its standing
     *  hint, which the unknown-name and cached-answer call-outs compose onto. */
    private fun applyEntries(parsed: List<XMakePackEntry>) {
        entries = parsed
        processHint = null
        repopulatePackNames()
        refreshFormatsRow()
        refreshPackageHint()
        setQueryInFlight(false)
    }

    private fun repopulatePackNames() {
        val previous = (packName.editor.item as? String)?.trim().orEmpty()
        packName.model = DefaultComboBoxModel(
            (listOf(ALL_PACKAGES) + entries.map(XMakePackEntry::name)).toTypedArray(),
        )
        packName.editor.item = previous.ifEmpty { ALL_PACKAGES }
    }

    /** Puts an enumeration process sentence on the package row, where it stays until the
     *  enumeration answers again - even while the name is retyped. */
    private fun setProcessHint(text: String) {
        processHint = text
        setPackageHint(text)
    }

    /** Rewrites the package row's hint from what the enumeration has answered and what the
     *  field holds: the standing line, or the empty-result sentence while nothing was
     *  listed - plus the unknown-name call-out for free text outside the answer, and the
     *  cache note when the answer came from the cache. A process sentence still standing
     *  wins outright; it is the news until the enumeration answers over it. */
    private fun refreshPackageHint() {
        processHint?.let { process ->
            setPackageHint(process)
            return
        }
        val base = if (entries.isEmpty()) EMPTY_RESULT_HINT else PACKAGE_COMMENT
        val name = packNameValue()
        val unknown = entries.isNotEmpty() && name.isNotEmpty() && entries.none { it.name == name }
        val cached = if (answeredFromCache) CACHED_SUFFIX else ""
        setPackageHint(
            when {
                unknown -> "$base. \"$name\" is not among the enumerated xpack() packages$cached"
                else -> base + cached
            },
        )
    }

    private fun setPackageHint(text: String) {
        packageComment(text)
    }

    private fun setFormatsHint(text: String) {
        formatsComment(text)
    }

    /** Flips the Refresh button between its idle and in-flight faces; both landing paths -
     *  an answer and a failure - restore it, so a finished query cannot leave the panel
     *  looking busy. */
    private fun setQueryInFlight(inFlight: Boolean) {
        val button = refreshButton ?: return
        button.isEnabled = !inFlight
        button.text = if (inFlight) REFRESH_LISTING else REFRESH
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
