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
package io.xmake.run.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Paints the coverage line highlights of the active suite into the open editors, with the
 * platform's own coverage colors: a filled bar in the editor's left line-marker gutter per
 * executed line, a line background where the color scheme defines one, and a hit count on
 * hover, the way the native coverage rendering reads.
 *
 * The platform's own editor annotator reaches a file's data through an engine hook that is
 * internal, with a default that answers nothing, so a third-party engine cannot feed it. This
 * painter is the same feature on public APIs instead: it listens for the suite events the
 * coverage manager publishes, resolves each open editor's file in the loaded data itself, and
 * paints the lines the report counted. Editors opened later are painted when they open;
 * coverage switched off clears every editor again.
 */
class XMakeCoverageEditorPainter(private val project: Project) {

    init {
        val manager = CoverageDataManager.getInstance(project)
        manager.addSuiteListener(object : CoverageSuiteListener {
            override fun coverageGathered(suite: com.intellij.coverage.CoverageSuite) = scheduleRepaint()

            override fun afterSuiteChosen() = scheduleRepaint()

            override fun coverageDataCalculated(bundle: com.intellij.coverage.CoverageSuitesBundle) = scheduleRepaint()
        }, project)
        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleRepaint()
            },
        )
    }

    /** Repaints on the EDT; a repaint without an active suite clears instead. */
    fun scheduleRepaint() {
        invokeLater {
            if (!project.isDisposed) repaint()
        }
    }

    private fun repaint() {
        val bundle = CoverageDataManager.getInstance(project).currentSuitesBundle
        val data = bundle?.coverageData
        var painted = 0
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<TextEditor>()
            .map(TextEditor::getEditor)
            .forEach { editor ->
                when {
                    data == null -> clear(editor)
                    else -> painted += paint(editor, data)
                }
            }
        Log.info("Coverage highlighters repainted: $painted line(s) in the open editors (data=${data != null})")
    }

    /**
     * Paints the lines of one editor from the loaded data, the way the native renderer layers
     * them: a marker highlighter carrying the gutter bar in the line-marker area, and a
     * background highlighter for the schemes that color covered lines. A file without data is
     * only cleared. Returns how many lines were painted.
     */
    fun paint(editor: Editor, data: ProjectData): Int {
        clear(editor)
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return 0
        val classData = data.getClassData(XMakeCoveragePaths.lookupKey(virtualFile.toNioPath())) ?: return 0
        val lines = classData.lines ?: return 0
        var paintedLines = 0
        val highlighters = mutableListOf<RangeHighlighter>()
        for (lineNumber in 1..lines.size - 1) {
            val lineData = classData.getLineData(lineNumber) ?: continue
            paintedLines++
            // The gutter bar, in the same marker layer the native range highlighter sits in.
            highlighters += editor.markupModel.addLineHighlighter(lineData.lineNumber - 1, 5999, null).apply {
                setLineMarkerRenderer(
                    CoverageBarRenderer(attributesKey(lineData), lineData),
                )
            }
            // The line background, for the schemes that paint one.
            val attributes = editor.colorsScheme.getAttributes(attributesKey(lineData))
            if (attributes?.backgroundColor != null) {
                highlighters += editor.markupModel.addLineHighlighter(
                    lineData.lineNumber - 1,
                    2999,
                    attributes,
                )
            }
        }
        if (highlighters.isNotEmpty()) {
            editor.putUserData(HIGHLIGHTERS, highlighters)
        }
        return paintedLines
    }

    /** Removes this painter's highlights from one editor. */
    fun clear(editor: Editor) {
        editor.getUserData(HIGHLIGHTERS)?.forEach(RangeHighlighter::dispose)
        editor.putUserData(HIGHLIGHTERS, null)
    }

    private fun attributesKey(lineData: LineData): TextAttributesKey = when (lineData.status) {
        LineStatus.FULLY -> CodeInsightColors.LINE_FULL_COVERAGE
        LineStatus.PARTIALLY -> CodeInsightColors.LINE_PARTIAL_COVERAGE
        else -> CodeInsightColors.LINE_NONE_COVERAGE
    }

    private companion object {
        private val Log = Logger.getInstance(XMakeCoverageEditorPainter::class.java)

        private val HIGHLIGHTERS: Key<MutableList<RangeHighlighter>> = Key.create("XMake.CoverageHighlighters")

        /** The statuses [LineData.getStatus] reports; the values are the runner library's own. */
        private object LineStatus {
            const val PARTIALLY = 1
            const val FULLY = 2
        }
    }
}

/**
 * The coverage bar of one line, drawn by the platform itself: implementing the filling
 * renderer with the line's coverage color key paints the same filled gutter bar the native
 * coverage rendering shows, stripes and hover tooltip included. The platform's own renderer
 * class is internal, but the contract it draws through is public.
 */
private class CoverageBarRenderer(
    private val coverageKey: TextAttributesKey,
    private val lineData: LineData,
) : FillingLineMarkerRenderer, ActiveGutterRenderer {

    override fun getTextAttributesKey(): TextAttributesKey = coverageKey

    /** The bar sits left of the line numbers, where the native coverage bar sits. */
    override fun getPosition(): com.intellij.openapi.editor.markup.LineMarkerRendererEx.Position =
        com.intellij.openapi.editor.markup.LineMarkerRendererEx.Position.LEFT

    override fun getTooltipText(): String? = when (lineData.status) {
        0 -> "Not covered"
        else -> "Covered, ${lineData.hits} hit(s)"
    }

    override fun doAction(editor: Editor, e: java.awt.event.MouseEvent) = Unit
}

