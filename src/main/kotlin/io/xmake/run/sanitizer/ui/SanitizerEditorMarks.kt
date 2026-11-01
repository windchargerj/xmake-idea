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
package io.xmake.run.sanitizer.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.sanitizer.SanitizerAnalysisService
import io.xmake.run.sanitizer.SanitizerReport
import io.xmake.run.sanitizer.SanitizerSeverity
import java.io.File

/**
 * Marks the lines the last sanitizer report blames, in every open editor: an error-stripe
 * mark on the right and a line background in the scheme's error or warning color, so the
 * findings stay visible where the code is, not only in the tree. Editors opened later are
 * marked when they open; a cleared report clears every editor again.
 */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
internal class SanitizerEditorMarks(private val project: Project) : Disposable {

    init {
        project.messageBus.connect(this).subscribe(
            SanitizerAnalysisService.TOPIC,
            SanitizerAnalysisService.Listener { scheduleRepaint() },
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleRepaint()
            },
        )
    }

    override fun dispose() = Unit

    fun scheduleRepaint() {
        invokeLater {
            if (!project.isDisposed) repaint()
        }
    }

    private fun repaint() {
        val report = SanitizerAnalysisService.getInstance(project).report
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<TextEditor>()
            .map(TextEditor::getEditor)
            .forEach { editor ->
                if (report == null) clear(editor) else paint(editor, report)
            }
    }

    private fun paint(editor: Editor, report: SanitizerReport) {
        clear(editor)
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val marks = blamedLines(virtualFile, report)
        if (marks.isEmpty()) return
        val highlighters = mutableListOf<RangeHighlighter>()
        for ((line, severity, _) in marks) {
            if (line >= editor.document.lineCount) continue
            val attributesKey = when (severity) {
                SanitizerSeverity.ERROR -> CodeInsightColors.ERRORS_ATTRIBUTES
                else -> CodeInsightColors.WARNINGS_ATTRIBUTES
            }
            val attributes = editor.colorsScheme.getAttributes(attributesKey)
            // The stripe mark carries the scheme's error/warning color; the finding's own
            // text stays in the view, which shows the full report beside the editor.
            highlighters += editor.markupModel.addLineHighlighter(line, HighlighterLayer.ERROR, attributes)
        }
        editor.putUserData(MARKS, highlighters)
    }

    fun clear(editor: Editor) {
        editor.getUserData(MARKS)?.forEach(RangeHighlighter::dispose)
        editor.putUserData(MARKS, null)
    }

    /** The one-based report lines that fall into [virtualFile], as zero-based editor lines. */
    private fun blamedLines(virtualFile: VirtualFile, report: SanitizerReport): List<Triple<Int, SanitizerSeverity, String>> {
        val mapper = report.toolkit?.let { toolkit ->
            report.hostWorkingDirectory?.let { RemotePathMapper.forToolkit(toolkit, it, project) }
        }
        val localPath = virtualFile.toNioPath()
        return report.issues.mapNotNull { issue ->
            val frame = issue.primaryFrame ?: return@mapNotNull null
            val framePath = frame.file ?: return@mapNotNull null
            val mapped = mapper?.toLocal(File(framePath).toPath())?.toFile()?.toPath()
            val matches = when {
                mapped != null && mapped == localPath -> true
                File(framePath).toPath() == localPath -> true
                else -> project.basePath != null &&
                        File(project.basePath, framePath).toPath() == localPath
            }
            if (!matches || frame.line == null) return@mapNotNull null
            Triple(frame.line - 1, issue.severity, "${issue.severity}: ${issue.type} - ${issue.message}")
        }
    }

    private companion object {
        private val MARKS: Key<MutableList<RangeHighlighter>> = Key.create("XMake.SanitizerEditorMarks")
    }
}
