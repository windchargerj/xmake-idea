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
package io.xmake.run.valgrind

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/** One stack frame of a Valgrind error with everything the XML report records for it. */
data class XMakeValgrindFrame(
    val instructionPointer: String = "",
    val binary: String = "",
    val function: String = "",
    val directory: String = "",
    val file: String = "",
    val line: Int? = null,
) {

    /** The source file as the tool reported it, when the frame names both directory and file. */
    val sourceFile: File?
        get() = if (directory.isNotEmpty() && file.isNotEmpty()) File(directory, file) else null
}

/** One problem description: the text Valgrind gives it and the stack recorded with it. */
data class XMakeValgrindDescription(
    val text: String,
    val frames: List<XMakeValgrindFrame>,
)

/** One error of a Valgrind XML report, shaped the way the native memory view groups it. */
data class XMakeValgrindError(
    val kind: String,
    val descriptions: List<XMakeValgrindDescription>,
    /** The suppression block the report carries for the error, used by the native suppress
     *  action; empty when the report has none. */
    val suppression: String = "",
) {

    /**
     * The text that titles the error. Valgrind reports no separate caption, so like the native
     * reader the first description's text takes that role.
     */
    val caption: String
        get() = descriptions.firstOrNull()?.text.orEmpty()

    /** The innermost frame pointing into sources: the location the finding is reported at. */
    val innermostSourceFrame: XMakeValgrindFrame?
        get() = descriptions.asSequence()
            .flatMap { it.frames.asSequence() }
            .firstOrNull { it.sourceFile != null }
}

/**
 * Reads the errors of a Valgrind XML report, keeping descriptions and stacks intact.
 *
 * The report is read as a stream rather than as a whole document, for two reasons: valgrind
 * writes it incrementally while it runs, so an unfinished report yields the errors that are
 * already complete, and a report is bounded only by the program's behavior, so it is never
 * loaded into memory as one tree.
 */
internal fun parseValgrindReport(report: File): List<XMakeValgrindError> {
    if (!report.isFile) return emptyList()
    val handler = ValgrindReportHandler()
    runCatching {
        val factory = SAXParserFactory.newInstance().apply {
            // The report is a local tool product, not trusted input: external entities stay
            // unfetched, whatever a doctype declares.
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        factory.newSAXParser().parse(report, handler)
    }
    // A report that is still being written ends mid-element; what was complete still counts.
    return handler.errors
}

/** Collects the errors of a Valgrind XML stream. The shape follows the native reader: every
 *  description element opens a description, a stack belongs to the description it follows, and
 *  a stack after a stack starts a description of its own. */
private class ValgrindReportHandler : DefaultHandler() {

    val errors = mutableListOf<XMakeValgrindError>()

    private var insideError = false
    private var insideSuppression = false
    private var insideExtendedDescription = false
    private var capturingExtendedText = false
    private var kind = ""
    private var suppression = ""
    private val descriptions = mutableListOf<ValgrindDescriptionBuilder>()
    private var frame: ValgrindFrameBuilder? = null
    private var text = StringBuilder()

    override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
        text = StringBuilder()
        if (!insideError) {
            if (qName == "error") {
                insideError = true
                kind = ""
                suppression = ""
                descriptions.clear()
            }
            return
        }
        when (qName) {
            // The suppression block carries its own kind and raw text; only the raw text is
            // read, and nothing inside it may touch the error.
            "suppression" -> {
                insideSuppression = true
                return
            }

            "what", "auxwhat" -> descriptions += ValgrindDescriptionBuilder()

            "xwhat", "xauxwhat" -> {
                descriptions += ValgrindDescriptionBuilder()
                insideExtendedDescription = true
            }

            "text" -> capturingExtendedText = insideExtendedDescription

            "stack" -> {
                if (descriptions.isEmpty() || descriptions.last().frames.isNotEmpty()) {
                    descriptions += ValgrindDescriptionBuilder()
                }
            }

            "frame" -> frame = ValgrindFrameBuilder()
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        if (!insideError) return
        if (insideSuppression && qName != "suppression" && qName != "rawtext") {
            text = StringBuilder()
            return
        }
        when (qName) {
            "suppression" -> {
                insideSuppression = false
                text = StringBuilder()
                return
            }

            "error" -> {
                insideError = false
                errors += XMakeValgrindError(
                    kind = kind,
                    descriptions = descriptions.map(ValgrindDescriptionBuilder::toDescription),
                    suppression = unwrapCData(suppression),
                )
            }

            "kind" -> kind = text.toString()

            "what", "auxwhat" -> currentDescription().text.append(text)

            "text" -> if (capturingExtendedText) {
                currentDescription().text.append(text)
                capturingExtendedText = false
            }

            "xwhat", "xauxwhat" -> insideExtendedDescription = false

            "ip" -> frame?.instructionPointer = text.toString()
            "obj" -> frame?.binary = text.toString()
            "fn" -> frame?.function = text.toString()
            "dir" -> frame?.directory = text.toString()
            "file" -> frame?.file = text.toString()
            "line" -> frame?.line = text.toString().trim().toIntOrNull()

            "frame" -> {
                frame
                    ?.takeIf { descriptions.isNotEmpty() }
                    ?.let { currentDescription().frames += it.toFrame() }
                frame = null
            }

            "rawtext" -> suppression = text.toString()
        }
        text = StringBuilder()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideError) {
            text.appendRange(ch, start, start + length)
        }
    }

    private fun currentDescription(): ValgrindDescriptionBuilder = descriptions.last()

    private class ValgrindDescriptionBuilder {
        val text = StringBuilder()
        val frames = mutableListOf<XMakeValgrindFrame>()

        fun toDescription(): XMakeValgrindDescription =
            XMakeValgrindDescription(text.toString(), frames.toList())
    }

    private class ValgrindFrameBuilder {
        var instructionPointer = ""
        var binary = ""
        var function = ""
        var directory = ""
        var file = ""
        var line: Int? = null

        fun toFrame(): XMakeValgrindFrame = XMakeValgrindFrame(
            instructionPointer = instructionPointer,
            binary = binary,
            function = function,
            directory = directory,
            file = file,
            line = line,
        )
    }
}

/** The report wraps the suppression in CDATA, which some writers escape a second time; both
 *  shapes unwrap to the same text. */
private fun unwrapCData(rawText: String): String = rawText
    .replace("<![CDATA[", "")
    .replace("]]>", "")

/**
 * Presents the errors of a finished Valgrind run the way the native runners do: a summary in
 * the run console with clickable source locations, error-stripe marks in open editors, and
 * nothing left for the user to import.
 */
internal fun presentValgrindErrors(
    project: Project,
    console: ConsoleView?,
    errors: List<XMakeValgrindError>,
    pathResolver: (File) -> File?,
) {
    if (errors.isEmpty()) return
    console?.print("\nValgrind found ${errors.size} problem(s):\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    errors.forEach { error ->
        val frame = error.innermostSourceFrame
        val resolved = frame?.sourceFile?.let(pathResolver)
        val virtualFile = resolved?.let(LocalFileSystem.getInstance()::findFileByIoFile)
        val line = frame?.line ?: 1
        if (resolved != null && virtualFile != null) {
            console?.printHyperlink(
                "${resolved.path}:$line",
                OpenFileHyperlinkInfo(project, virtualFile, (line - 1).coerceAtLeast(0), 0),
            )
            console?.print(": [${error.kind}] ${error.caption}\n", ConsoleViewContentType.ERROR_OUTPUT)
            markValgrindError(project, resolved, line)
        } else {
            console?.print("[${error.kind}] ${error.caption}\n", ConsoleViewContentType.ERROR_OUTPUT)
        }
    }
}

/** Marks the error lines of open editors with an error stripe; the marks of the previous run
 *  are replaced. */
private fun markValgrindError(project: Project, file: File, line: Int) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@invokeLater
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@invokeLater
        val attributes = TextAttributes().apply { errorStripeColor = JBColor.RED }
        val highlighters = mutableListOf<RangeHighlighter>()
        EditorFactory.getInstance().getEditors(document, project).forEach { editor ->
            val logicalLine = (line - 1).coerceIn(0, document.lineCount - 1)
            highlighters += editor.markupModel.addLineHighlighter(
                logicalLine,
                HighlighterLayer.ERROR,
                attributes,
            )
        }
        if (highlighters.isNotEmpty()) {
            replaceValgrindMarks(project, highlighters)
        }
    }
}

private val valgrindMarks = mutableMapOf<Project, List<RangeHighlighter>>()

private fun replaceValgrindMarks(project: Project, highlighters: List<RangeHighlighter>) {
    valgrindMarks.remove(project)?.forEach(RangeHighlighter::dispose)
    valgrindMarks[project] = highlighters
}
