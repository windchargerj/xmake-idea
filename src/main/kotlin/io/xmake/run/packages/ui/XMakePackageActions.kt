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
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.utils.cli.XMakeCommandLine
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.packages.XMakePackagesService
import io.xmake.utils.cli.XMakePackageSpec
import io.xmake.utils.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The panel's verbs: the composed action table, the two confirmation shapes, and the
 * bespoke trio (refresh, the installed toggle, the console hop). One collaborator of
 * [XMakePackagesPanel]; reads the panel's selection and info helpers, owns nothing the
 * panel needs back except the toolbar.
 */
internal class XMakePackageActions(private val panel: XMakePackagesPanel) {

    /** Drives only the Show-in-Console hop onto the EDT; cancelled with the panel. */
    private val consoleScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    internal fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RefreshAction(),
            Separator.create(),
            *packageActions.map { it as AnAction }.toTypedArray(),
            Separator.create(),
            ShowInConsoleAction(),
        )
        return ActionManager.getInstance()
            .createActionToolbar("XMakePackagesView", group, true)
            .apply { targetComponent = panel }
    }

    /** The one mutation shape every store-affecting action reads: the title, the ask's
     *  sentence, and the command built on the factory - whose toolkit resolution runs
     *  off the EDT inside [XMakePackagesService.prepareCommand], while the approval
     *  stays on it. */
    internal fun confirmAndRun(
        title: String,
        what: String,
        prepare: XMakeCommandFactory.() -> XMakeCommand,
    ) {
        panel.service.prepareCommand(prepare = prepare) { command ->
            runConfirmed(title, what, command)
        }
    }

    /** The approval itself: the exact command line, the packages it touches, then the
     *  run with `-y` already baked in. Mutations only - the read-only asks take
     *  [runReported] and stand in no queue for a yes. */
    private fun runConfirmed(title: String, what: String, command: XMakeCommand) {
        // TODO(v2): name the expected xmake-requires.lock effect here when the policy is on
        // (docs/roadmap-designs/xrepo-packages.md); the diff balloon ships with it.
        if (Messages.showYesNoDialog(
                panel.project,
                "xmake will run:\n\n    ${command.commandLine.commandLineString}\n\n$what\n" +
                    "The output streams into the XMake console.",
                title,
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return
        panel.service.runPackageCommand(command, what)
    }

    /** The one read-only shape: what ran streams into the console, no approval in
     *  front - a query never queues for a yes. The symmetric sibling of
     *  [confirmAndRun], by the command/query line the DSL already draws. */
    internal fun runReported(what: String, prepare: XMakeCommandFactory.() -> XMakeCommand) {
        panel.service.prepareCommand(prepare = prepare) { command ->
            panel.service.runPackageCommand(command, what)
        }
    }

    /** The Install flow the toolbar button, a double-click on a row, and the details
     *  page's version links share. [ask] is the xmake package ask: a bare name, or a
     *  name pinned to one version (`rsync 3.4.1`) - parsed into a spec here, the one
     *  grammar the command ever sees. */
    internal fun installPackage(ask: String) {
        val spec = XMakePackageSpec.parse(ask)
        confirmAndRun("Install package", "Installing '${spec.render()}' - xmake fetches, builds, and installs it.") {
            createRequireInstall(spec)
        }
    }

    /** The Uninstall flow the toolbar button and the details page's version link share.
     *  [ask] is the xmake package ask: a bare name, or a name pinned to one version
     *  (`zstd 1.5.6`) - the pin decides which installed version's directory xmake removes. */
    internal fun uninstallPackage(ask: String) {
        val asked = XMakePackageSpec.parse(ask)
        val row = panel.selectedRow()
        val name = row?.name ?: asked.name
        // Bare-name uninstall resolves "latest" and misses a non-latest install; the
        // version the row or the info vouches for is what gets pinned - the row of the
        // installed view names its version outright, a declared row falls to the info's
        // resolved version, then to the first the store holds, then to the link's pin.
        val version = row?.takeIf { it.isInstalled }?.version
            ?: panel.infoFor(name)?.version?.takeIf { panel.infoFor(name)?.installed == true }
            ?: panel.onDiskVersions(name).firstOrNull()
            ?: asked.version
        val spec = XMakePackageSpec(name).pinnedTo(version)
        confirmAndRun("Uninstall package", "Uninstalling '${spec.render()}'.") {
            createRequireUninstall(spec)
        }
    }


    /**
     * The package actions as one composed table instead of one class per verb: a row is
     * a fact set - what it says, when it enables, what it runs - and [PanelAction]
     * renders the AnAction shell around any row. Gates and executions all read one
     * composed pass of the decision state ([PanelState]), so no action re-derives its
     * own truth between dimming and running. Refresh, the installed toggle, and the
     * console hop keep their bespoke classes: they carry state or leave this panel's
     * grammar.
     */
    private val packageActions: List<PanelAction> = listOf(
        PanelAction(
            "Install",
            "Install the selected package after confirming the exact command",
            AllIcons.Actions.Install,
            gate = { it.idle && it.selected && !it.installed },
        ) { installPackage(it.name ?: return@PanelAction) },
        command(
            "Upgrade",
            "Upgrade the selected package, or every declared one without a selection, to the " +
                "newest version its requirement allows (xmake require --upgrade)",
            AllIcons.Actions.Upload,
            gate = { it.idle },
            sentence = { name ->
                if (name == null) {
                    "Upgrading every declared package to the newest version its requirement allows."
                } else {
                    "Upgrading '$name' to the newest version its requirement allows."
                }
            },
            run = { name -> createRequireUpgrade(name?.let(XMakePackageSpec::parse)) },
        ),
        PanelAction(
            "Uninstall",
            "Uninstall the selected package after confirming the exact command",
            AllIcons.Actions.Uninstall,
            gate = { it.idle && it.selected && it.installed },
        ) { uninstallPackage(it.name ?: return@PanelAction) },
        query(
            "Fetch",
            "Fetch the package's library info from the repository (xmake require --fetch)",
            AllIcons.General.Information,
            gate = { it.idle && it.selected },
            sentence = { name -> "Fetching the library info of '$name'." },
            run = { name -> createRequireFetch(XMakePackageSpec.parse(name)) },
        ),
        command(
            "Download source",
            "Download the package's source archive without installing (xmake require --download; " +
                "xmake 3.1.2+ - the button answers for the toolkit it runs on)",
            com.intellij.icons.AllIcons.Actions.Download,
            gate = { it.idle && it.selected && it.supports({ require { download = true } }) },
            sentence = { name -> "Downloading the source archive of '$name'." },
            run = { name -> createRequireDownload(XMakePackageSpec.parse(requireNotNull(name))) },
        ),
        query(
            "Check support",
            "Whether this package is supported on the current platform (xmake require --check; " +
                "xmake 3.1.2+ - the button answers for the toolkit it runs on)",
            com.intellij.icons.AllIcons.Actions.Help,
            gate = { it.idle && it.selected && it.supports({ require { check = true } }) },
            sentence = { name -> "Checking whether '$name' is supported here." },
            run = { name -> createRequireCheck(XMakePackageSpec.parse(name)) },
        ),
        command(
            "Export",
            "Export the installed package and its dependencies to a portable archive (xmake require --export)",
            com.intellij.icons.AllIcons.ToolbarDecorator.Export,
            gate = { it.idle && it.selected && it.installed },
            sentence = { name ->
                // The destination is xmake's own default: a "packages" directory in the
                // project. Naming it is what makes the verb honest - an export whose
                // destination nobody knows reads as a no-op.
                "Exporting the installed '$name' and its dependencies into the project's 'packages' directory."
            },
            run = { name -> createRequireExport(XMakePackageSpec.parse(requireNotNull(name))) },
        ),
        PanelAction(
            "Import",
            "Import previously exported packages (xmake require --import)",
            com.intellij.icons.AllIcons.ToolbarDecorator.Import,
            gate = { it.idle },
        ) { _ ->
            // --import takes package NAMES, searched in --packagedir (default "packages"
            // in the project) - xmake's own help text and its import implementation read
            // the arguments as requires, not as an archive path.
            com.intellij.openapi.ui.Messages.showInputDialog(
                panel.project,
                "Package to import from the project's 'packages' directory:",
                "Import packages",
                null,
            )?.trim()?.takeUnless(String::isEmpty)?.let { name ->
                confirmAndRun("Import packages", "Importing '$name' from the 'packages' directory.") {
                    createRequireImport(XMakePackageSpec.parse(name))
                }
            }
        },
        command(
            "Clean",
            "Clear the package caches and uninstall the packages nothing references; with a " +
                "selection, just that package (xmake require --clean)",
            com.intellij.icons.AllIcons.Actions.GC,
            gate = { it.idle },
            sentence = { name ->
                // xmake's own semantics, both 2.8.7 and 3.1.2: --clean clears the caches
                // and uninstalls the NOT-REFERENCED packages - the projects still using
                // one keep it.
                if (name == null) {
                    "Clearing the package caches and uninstalling the packages no project references."
                } else {
                    "Clearing the caches of '$name' and uninstalling it if nothing references it."
                }
            },
            run = { name -> createRequireClean(name?.let { listOf(XMakePackageSpec.parse(it)) }.orEmpty()) },
        ),
    )

    /** A store-affecting row: the approval stands in front of the command. */
    private fun command(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        gate: (PanelState) -> Boolean,
        sentence: (String?) -> String,
        run: XMakeCommandFactory.(String?) -> XMakeCommand,
    ): PanelAction = PanelAction(text, description, icon, gate) { state ->
        confirmAndRun(text, sentence(state.name)) { run(state.name) }
    }

    /** A read-only row: what ran streams into the console, no approval in front. Its
     *  gate requires a selection, so the ask always names one. */
    private fun query(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        gate: (PanelState) -> Boolean,
        sentence: (String) -> String,
        run: XMakeCommandFactory.(String) -> XMakeCommand,
    ): PanelAction = PanelAction(text, description, icon, gate) { state ->
        val name = state.name ?: return@PanelAction
        runReported(sentence(name)) { run(name) }
    }

    /** The AnAction shell every table row renders through: one gate, one perform, both
     *  reading the same composed pass of the decision state. */
    private inner class PanelAction(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        private val gate: (PanelState) -> Boolean,
        private val perform: (PanelState) -> Unit,
    ) : DumbAwareAction(text, description, icon) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = gate(state())
        }

        override fun actionPerformed(event: AnActionEvent) {
            perform(state())
        }
    }

    /** Composes one pass of the decision state from the panel's own sources. */
    private fun state(): PanelState {
        val row = panel.selectedRow()
        val name = row?.name
        return PanelState(
            idle = !panel.service.busy,
            selected = row != null,
            name = name,
            installed = name != null && (
                panel.infoFor(name)?.installed == true ||
                    row?.isInstalled == true ||
                    panel.onDiskVersions(name).isNotEmpty()
                ),
            capable = { ask -> panel.service.supports(ask) },
        )
    }

    /** One composed pass of the decision state: built once per gate check and once per
     *  execution, read by every row - no action re-derives its own truth. The facts are
     * one word each and the rows compose them into their own sentences: a gate reads as
     * its English (`idle && selected && !installed` is the Install button, literally).
     *
     * [installed] folds the three evidences - the info's marker, the inventory row's
     * own proof, the store scan's versions - into the one question a row asks. */
    private class PanelState(
        val idle: Boolean,
        val selected: Boolean,
        val name: String?,
        val installed: Boolean,
        val capable: (XMakeCommandLine.() -> Unit) -> Boolean,
    ) {
        fun supports(ask: XMakeCommandLine.() -> Unit): Boolean = capable(ask)
    }


    /** Toggles the table between the declared packages and the `--scan` inventory of
     *  what is actually installed. The scan runs once on first show; the snapshot keeps
     *  the inventory, so re-entering the view costs nothing until a refresh drops it. */
    private inner class ShowInstalledToggle : DumbAwareAction(
        "Installed",
        "Show the packages actually installed (xmake require --scan)",
        com.intellij.icons.AllIcons.Nodes.PackageLocal,
    ) {

        private var showing = false

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !panel.service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            showing = !showing
            if (showing) {
                panel.service.scanInstalled()
            } else {
                panel.service.exitInstalled()
            }
        }
    }


    private inner class RefreshAction :
        DumbAwareAction("Refresh", "Re-probe the declared packages and the dependency graph", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !panel.service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            panel.service.refresh()
        }
    }

    private inner class ShowInConsoleAction : DumbAwareAction(
        "Show in Console",
        "Activate the XMake console, where the package commands stream their output",
        AllIcons.Actions.Preview,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) {
            consoleScope.launch {
                try {
                    XMakeConsoleManager.getInstance(panel.project).awaitReady().showOutput()
                } catch (error: Exception) {
                    // The console does not exist in a disposed panel.project; nothing to show then.
                    if (!panel.project.isDisposed) {
                        panel.project.notify(
                            "The XMake console is not available",
                            StringUtil.escapeXmlEntities(error.message ?: ""),
                        )
                    }
                }
            }
        }
    }

    /** The console hop's scope dies with the panel. */
    fun dispose() {
        consoleScope.cancel()
    }
}
