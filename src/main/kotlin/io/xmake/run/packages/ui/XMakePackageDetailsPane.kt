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
package io.xmake.run.packages.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.run.packages.XMakePackageInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants

/**
 * The packages page's right half: the selected package's header, its version list with
 * the per-version install and uninstall links, and the facts under them. One
 * collaborator of [XMakePackagesPanel]; reads the panel's snapshot helpers, renders
 * from the service's published state.
 */
internal class XMakePackageDetailsPane(private val panel: XMakePackagesPanel) {

    private val detailsName = JBLabel().apply {
        font = JBFont.create(UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f))
        alignmentY = 0.5f
    }

    /** The badges the title row carries: the state the row reads, then the platforms the
     *  package answers - the Marketplace header's tag row, attached to the name. */
    private val detailsBadges = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        alignmentY = 0.5f
    }

    /** The details pane's meta line: the state, then the project's ask, repository, and
     *  resolved version in one gray run. */
    private val detailsMeta = JBLabel().apply { foreground = infoForeground() }

    /** The repository page, as a link at the header's right edge - the one place a
     *  reader leaves the IDE to. The link data is the url, so a render replaces the
     *  whole label rather than mutating it. */
    private var detailsRepoLink: JComponent = JPanel()

    /** The header's link row: the package's own project page and its documentation,
     *  told from the source archive the query reports. A forge repository answers both -
     *  the project page is its root, the docs its readme; any other host answers its
     *  site root as the homepage and nothing as docs. Neither link goes to the package
     *  definition or the repository index: those are plumbing, not the project. */
    private fun packageLinks(name: String?): JComponent? {
        val (docs, guide) = xmakePackageLinks(name)
        val links = javax.swing.Box.createHorizontalBox()
        var any = false
        fun addLink(label: String, url: String) {
            if (any) links.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)))
            links.add(
                com.intellij.ui.components.labels.LinkLabel<String>(
                    label,
                    com.intellij.icons.AllIcons.Ide.External_link_arrow,
                ).apply {
                    verticalAlignment = javax.swing.SwingConstants.CENTER
                    setListener(
                        { _, target: String? ->
                            target?.takeIf(String::isNotEmpty)?.let(com.intellij.ide.BrowserUtil::browse)
                        },
                        url,
                    )
                },
            )
            any = true
        }
        docs?.let { addLink("Docs", it) }
        guide?.let { addLink("Guide", it) }
        return links.takeIf { any }
    }

    /** The header's right slot: the repository link when the query carried one, empty
     *  otherwise - a render replaces its content. */
    private val detailsRepoSlot = BorderLayoutPanel().apply { isOpaque = false }

    /** The second header row: the meta facts at the left, the repository link at the
     *  right edge - the row that keeps the header from reading as an empty band. */
    private val detailsHeaderFacts = BorderLayoutPanel().apply {
        isOpaque = false
        addToCenter(detailsMeta)
        addToRight(detailsRepoSlot)
    }

    /** The details page's body: one HTML document, so the typography is real - a
     *  paragraph for the description, lettered section headings, indented dependency
     *  entries, install and uninstall links beside each version. The links route back
     *  to the same confirmation flows the toolbar uses, and the loading row embeds its
     *  spinner here - hence the text pane, the editor pane's component-embedding one. */
    private val detailsBody = JTextPane().apply {
        isEditable = false
        isOpaque = false
        contentType = "text/html"
        editorKit = htmlKit()
        border = JBUI.Borders.empty(8, 12, 12, 12)
        addHyperlinkListener { event ->
            if (event.eventType != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val target = event.description ?: return@addHyperlinkListener
            when {
                target.startsWith("action://install/") -> panel.installPackage(target.removePrefix("action://install/"))
                target.startsWith("action://uninstall/") -> panel.uninstallPackage(target.removePrefix("action://uninstall/"))
            }
        }
    }

    /** The info query's spinner: the loading row embeds it in the details body, so one
     *  icon animates the wait - the query rides the executor lane, over SSH in the
     *  worst case, and the icon says the click was heard - reused across renders. */
    internal val infoLoading = AsyncProcessIcon("XMakePackagesInfoLoading")

    private val detailsHeader = object : JPanel(GridBagLayout()) {
        override fun getPreferredSize(): Dimension {
            val own = super.preferredSize
            val band = panel.headerBandHeight()
            return Dimension(own.width, if (band > 0) band else own.height)
        }
    }.apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
            JBUI.Borders.empty(10, 12, 8, 12),
        )
        fun row(gridy: Int, component: JComponent, stretch: Boolean = true) = add(
            component,
            GridBagConstraints().apply {
                this.gridy = gridy
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                fill = if (stretch) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                insets = JBUI.insets(if (gridy == 0) 0 else 6, 0)
            },
        )
        row(0, javax.swing.Box.createHorizontalBox().apply {
            add(detailsName)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
            add(detailsBadges)
        })
        row(1, detailsHeaderFacts)
    }


    /** The details pane as the Marketplace page lays it out: the header fixed, the page
     *  scrolling under it. */
    private val detailsPane = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = mainBackground()
        add(detailsHeader, BorderLayout.NORTH)
        add(
            JBScrollPane(
                detailsBody,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply { border = JBUI.Borders.empty() },
            BorderLayout.CENTER,
        )
    }

    /** The nothing-selected card the Marketplace page shows instead of the details. */
    private val detailsEmpty = JBPanelWithEmptyText().apply {
        isOpaque = true
        background = mainBackground()
        emptyText.text = "Select a package to read its details"
    }

    /** The right pane of the split; swaps between the details page and the
     *  nothing-selected card - the loading state renders in the page's body. */
    internal val detailsCard = JPanel(BorderLayout())

    internal fun renderDetails(name: String?) {
        detailsPackage = name
        when {
            name == null -> {
                showDetailsCard(detailsEmpty)
                return
            }
            // No info record and no failure record: the quiet probe is in flight - the
            // spinner holds until its answer lands on the snapshot.
            panel.infoFor(name) == null && panel.failureFor(name) == null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, panel.tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                renderRepoLink(null)
                detailsBody.text = wrapHtml("<p class='g'>Loading '${esc(name)}'...</p>")
                detailsBody.caretPosition = 0
                detailsBody.insertComponent(infoLoading)
            }
            panel.infoFor(name) == null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, panel.tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                renderRepoLink(null)
                // The probe's own complaint when there is one - "xmake answered
                // garbage" reads differently from "never asked" or "the process died".
                val why = panel.failureFor(name)
                detailsBody.text = wrapHtml(
                    buildString {
                        append("<p class='g'>No info available for '${esc(name)}'")
                        why?.let { append(" - ").append(esc(it)) }
                        panel.service.lastError?.let { append(": ").append(esc(it)) }
                        append(".</p><p class='g'>")
                        append(panel.unpackHint()?.let { esc(it) } ?: "The xmake on this host may not answer require --info; updating xmake may fix it")
                        append("</p>")
                    },
                )
                detailsBody.caretPosition = 0
            }
            else -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                val row = panel.tableModel.items.firstOrNull { it.name == name }
                val info = panel.infoFor(name)
                renderBadges(name, row, info)
                detailsMeta.text = when {
                    // A package that exists nowhere still reports version "latest"; the
                    // missing repo is the "not found" signal, not a failed query.
                    info != null && info.repoName == null -> "not found in the configured repositories"
                    else -> listOfNotNull(
                        row?.requirement?.let { "requires $it" },
                        info?.repoName ?: row?.repo,
                        info?.version ?: row?.version?.let { "v$it" },
                    ).joinToString(" · ").ifEmpty { " " }
                }
                renderRepoLink(name)
                detailsBody.text = wrapHtml(detailsHtml(name))
                detailsBody.caretPosition = 0
            }
        }
    }

    /** The badges the title row carries: the row's state first - its tone carries the
     *  meaning, so the pill is a quiet tint rather than a shouting slab - then the
     *  platforms the package answers. Read fresh on every render, like the meta line. */
    private fun renderBadges(name: String, row: PackageRow?, info: XMakePackageInfo?) {
        detailsBadges.removeAll()
        val state = when {
            info?.installed == true -> INSTALLED
            row?.isHit == true -> AVAILABLE
            else -> DECLARED
        }
        detailsBadges.add(Chip(state, badgeForeground(state)))
        info?.platforms?.forEach { platform ->
            detailsBadges.add(Chip(platform, infoForeground()))
        }
        detailsBadges.revalidate()
        detailsBadges.repaint()
    }

    /** Puts the repository link - or nothing - in the header's right slot. */
    private fun renderRepoLink(name: String?) {
        detailsRepoSlot.removeAll()
        packageLinks(name)?.let { detailsRepoSlot.addToRight(it) }
        detailsRepoSlot.revalidate()
        detailsRepoSlot.repaint()
    }

    /** Swaps the right pane between the two cards: the nothing-selected state and the
     *  details page - the loading state renders in the page's body. */
    private fun showDetailsCard(card: JComponent) {
        detailsCard.removeAll()
        detailsCard.add(card, BorderLayout.CENTER)
        detailsCard.revalidate()
        detailsCard.repaint()
    }

    /** The HTML kit the details page renders with: the label font as the base, the
     *  metadata gray for captions, the platform link color for the actions. */
    private fun htmlKit(): javax.swing.text.html.HTMLEditorKit {
        val kit = javax.swing.text.html.HTMLEditorKit()
        val base = UIUtil.getLabelFont()
        val gray = ColorUtil.toHex(infoForeground())
        val link = ColorUtil.toHex(JBColor.namedColor("Link.activeForeground", JBColor(0x1D73BF, 0x4A88C7)))
        val green = ColorUtil.toHex(badgeForeground(INSTALLED))
        val mono = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, base.size).family
        kit.styleSheet.apply {
            addRule("body { font-family: ${base.family}; font-size: ${base.size}pt; }")
            addRule("p { margin: 0 0 8px; }")
            addRule(".sec { font-weight: bold; color: #$gray; margin: 14px 0 4px; }")
            addRule(".dep { margin: 0 0 8px 12px; }")
            addRule(".v { color: #$gray; }")
            addRule(".g { color: #$gray; }")
            // The state word wears the badge's green: the table's Status column, the
            // header's badge, and this word read as one language.
            addRule(".ok { color: #$green; }")
            addRule(".count { color: #$gray; font-weight: normal; }")
            // Paths read in the mono face one point down - the editor's own convention
            // for anything a user may copy into a shell.
            addRule(".mono { font-family: $mono; font-size: ${base.size - 1}pt; color: #$gray; }")
            addRule("a { color: #$link; text-decoration: none; }")
        }
        return kit
    }

    /** Escapes [text] for the HTML document the details page renders. */
    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    /** The selected package the details page currently renders; the version links route
     *  their confirmation flows through it. */
    private var detailsPackage: String? = null

    /** Wraps [body] in the document shell the kit renders. */
    private fun detailsHtml(name: String): String {
        val info = panel.infoFor(name) ?: return ""
        val row = panel.tableModel.items.firstOrNull { it.name == name }
        val page = StringBuilder()

        // The description leads: it is what the user came to read. The versions follow
        // before the dependencies - choosing which version to install or remove is the
        // action the page serves, and it must not sit below fourteen entries of closure.
        info.description?.let { page.append("<p>").append(esc(it)).append("</p>") }

        // The versions the view knows of: the ones the search answered, plus every
        // version the store holds on disk (the targeted scan rode the info query). Each
        // version reads its true state - the one this project resolves to and references
        // is "in use", the rest the store holds are "installed", the others "available" -
        // and each pin installs or uninstalls. xmake's grammar pins a version with a
        // space ("zstd 1.5.6") - the @ separates a repository from the name. The
        // toolbar's actions stay whole-package - this section is where a version chooses.
        val versions = row?.versions.orEmpty()
        val installedNow = if (info.installed) info.version else null
        val onDisk = panel.onDiskVersions(name)
        val allVersions = versions + onDisk.filter { onDiskVersion -> versions.none { it == onDiskVersion } }
        if (allVersions.isNotEmpty()) {
            page.append("<p class='sec'>VERSIONS <span class='count'>(")
                .append(allVersions.size).append(")</span></p>")
            allVersions.forEach { version ->
                val pinned = "$name $version"
                page.append("<div class='dep'><b>").append(esc(version)).append("</b>")
                when {
                    version == installedNow -> {
                        page.append(" <span class='ok'>in use</span> <a href='action://uninstall/")
                            .append(esc(pinned)).append("'>Uninstall</a>")
                    }
                    version in onDisk -> {
                        page.append(" <span class='ok'>installed</span> <a href='action://uninstall/")
                            .append(esc(pinned)).append("'>Uninstall</a>")
                    }
                    else -> {
                        page.append(" <span class='g'>available</span> <a href='action://install/")
                            .append(esc(pinned)).append("'>Install</a>")
                    }
                }
                page.append("</div>")
            }
        }

        // The facts sit before the closure: the paths answer "where is it", which a
        // reader checks before asking what it needs.
        page.append("<p class='sec'>INFO</p>")
        page.append("<div class='dep'>")
        info.installdir?.let {
            page.append("<span class='g'>Install directory:</span> <span class='mono'>")
                .append(esc(elideMiddle(it, 48))).append("</span><br>")
        }
        info.cachedir?.let {
            page.append("<span class='g'>Cache directory:</span> <span class='mono'>")
                .append(esc(elideMiddle(it, 48))).append("</span><br>")
        }
        page.append("</div>")

        // The closure closes the page - the deepest detail, read last.
        val closure = panel.service.snapshot?.closures?.get(name).orEmpty()
        val asked = panel.bareName(name)
        val dependencies = closure.filter { panel.bareName(it.require) != asked }
        val declaredDependencies = panel.service.snapshot?.depgraph?.packages
            ?.firstOrNull { it.name == name }?.deps
        if (dependencies.isNotEmpty()) {
            page.append("<p class='sec'>DEPENDENCIES - ${dependencies.size}</p>")
            dependencies.forEach { dependency ->
                page.append("<div class='dep'><b>").append(esc(dependency.require.ifEmpty { "?" })).append("</b>")
                dependency.version?.let { page.append(" <span class='v'>").append(esc(it)).append("</span>") }
                dependency.description?.let { page.append("<br><span class='g'>").append(esc(it)).append("</span>") }
                page.append("</div>")
            }
        } else if (!declaredDependencies.isNullOrEmpty()) {
            page.append("<p class='sec'>DEPENDENCIES</p><div class='dep'><span class='g'>")
                .append(esc(declaredDependencies.joinToString(", ")))
                .append("</span></div>")
        }
        return page.toString()
    }

    /** Wraps [body] in the document shell the kit renders. */

    private fun wrapHtml(body: String): String =
        "<html><head></head><body>$body</body></html>"

    /** Cuts [text] through the middle once it passes [maxChars], keeping both ends. */
    private fun elideMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = maxChars / 2
        val tail = maxChars - head
        return text.take(head) + " ... " + text.takeLast(tail)
    }

    /** The loading spinner dies with the panel. */
    fun dispose() {
        infoLoading.dispose()
    }
}
