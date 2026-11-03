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
package io.xmake.run.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import io.xmake.run.command.RemotePathMapper
import java.io.File

/**
 * Feeds the analysis findings into the platform's problem machinery: the files the last
 * report blames light up in the Problems view and the project view, the way external
 * tools' findings do when the platform owns them.
 *
 * The solver writes from a background thread only - it highlights as it goes - and every
 * report replaces the previous one's files, so stale marks cannot outlive their run.
 */
@Service(Service.Level.PROJECT)
internal class AnalysisProblemReporter(private val project: Project) {

    private var reportedFiles: Set<VirtualFile> = emptySet()

    init {
        project.messageBus.connect().subscribe(
            AnalysisService.TOPIC,
            AnalysisService.Listener { report -> replace(report) },
        )
    }

    private fun replace(report: AnalysisReport?) {
        val wolf = WolfTheProblemSolver.getInstance(project)
        val byFile = report?.let(::resolveFindings) ?: emptyMap()
        val files = byFile.keys
        val removed = reportedFiles - files
        reportedFiles = files
        ApplicationManager.getApplication().executeOnPooledThread {
            removed.forEach { file -> wolf.reportProblems(file, emptyList()) }
            byFile.forEach { (file, findings) ->
                val problems = findings.mapNotNull { (line, column, message) ->
                    wolf.convertToProblem(file, line, column, arrayOf(message))
                }
                wolf.reportProblems(file, problems)
            }
            if (byFile.isEmpty() && removed.isEmpty()) {
                Log.debug("No reportable findings for the Problems view")
            }
        }
    }

    /** The report's findings grouped by their local file, with 1-based positions. The
     *  path ladder is the shared one in [RemotePathMapper.localCandidates]; only the
     *  existence test is this consumer's own. */
    private fun resolveFindings(report: AnalysisReport): Map<VirtualFile, List<Triple<Int, Int, String>>> {
        val mapper = report.toolkit?.let { toolkit ->
            report.hostWorkingDirectory?.let { RemotePathMapper.forToolkit(toolkit, it, project) }
        }
        val byFile = mutableMapOf<VirtualFile, MutableList<Triple<Int, Int, String>>>()
        for (issue in report.issues) {
            val frame = issue.primaryFrame ?: continue
            val path = frame.file ?: continue
            val line = frame.line ?: continue
            val local = RemotePathMapper.localCandidates(mapper, File(path).toPath(), project)
                .firstOrNull { candidate -> candidate.toFile().isFile }
                ?: continue
            val virtual = LocalFileSystem.getInstance().findFileByIoFile(local.toFile()) ?: continue
            byFile.getOrPut(virtual) { mutableListOf() } += Triple(line, frame.column ?: 1, "${issue.type}: ${issue.message}")
        }
        return byFile
    }

    companion object {
        private val Log = Logger.getInstance(AnalysisProblemReporter::class.java)

        fun getInstance(project: Project): AnalysisProblemReporter = project.service()
    }
}
