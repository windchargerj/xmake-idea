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
package io.xmake.run.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import io.xmake.XMakePlatformTestCase
import io.xmake.run.XMakeRunConfiguration
import io.xmake.run.XMakeRunConfigurationType
import java.io.File
import java.nio.file.Files

/**
 * Runs the platform's coverage flow against the XMake engine: suite creation through the engine,
 * the bundle the platform builds from a suite, and the data load through [XMakeCoverageRunner].
 * Each of these steps failed at some point during development (a null-returning creator, an
 * engine-identity assertion, a report format the parser did not read), so they are asserted
 * together against real fixture files.
 */
class XMakeCoverageIntegrationTest : XMakePlatformTestCase() {

    private val tempRoots = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempRoots.forEach(FileUtil::delete)
            tempRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testSuiteCreationReturnsASuiteWithTheRegisteredEngine() {
        val engine = XMakeCoverageEngine()
        val suite = engine.createCoverageSuite(
            "XMake Coverage",
            project,
            XMakeCoverageRunner(),
            directoryProvider(dataRoot()),
            System.currentTimeMillis(),
        )

        /** The platform's creators return null unless the engine overrides them. */
        assertNotNull("suite", suite)
        assertSame(engine, suite.coverageEngine)
        /** The platform builds this in coverageGathered() and asserts that every suite in it
         *  returns the same engine instance. */
        assertSame(engine, CoverageSuitesBundle(suite).coverageEngine)
    }

    fun testBundleLoadsDataThroughTheRunner() {
        val dataRoot = dataRoot()
        val source = File(dataRoot, "src/main.cpp").apply {
            parentFile.mkdirs()
            writeText("int main() { return 0; }\n")
        }
        File(dataRoot, "main.cpp.gcda.gcov").writeText(
            """
            version:8.3.0
            file:${source.absolutePath}
            lcount:10,3,0
            lcount:11,0,1
            """.trimIndent(),
        )

        val suite = registeredEngine().createCoverageSuite(
            "XMake Coverage",
            project,
            XMakeCoverageRunner(),
            directoryProvider(dataRoot),
            System.currentTimeMillis(),
        )
        assertNotNull(suite)

        val data = CoverageSuitesBundle(suite).coverageData

        assertNotNull("coverage data must load through the runner", data)
        val classData = data!!.getClassData(XMakeCoveragePaths.lookupKey(source.toPath()))
        assertNotNull("class data for the fixture source", classData)
        assertEquals(3, classData!!.getLineData(10).hits)
        assertEquals(0, classData.getLineData(11).hits)
    }

    fun testAnnotatorIsASharedProjectService() {
        val engine = registeredEngine()
        assertSame(engine.getCoverageAnnotator(project), engine.getCoverageAnnotator(project))
    }

    fun testGatheredSuiteBecomesCurrent() {
        val dataRoot = dataRoot()
        val source = File(dataRoot, "src/main.cpp").apply {
            parentFile.mkdirs()
            writeText("int main() { return 0; }\n")
        }
        File(dataRoot, "main.cpp.gcda.gcov").writeText(
            """
            version:8.3.0
            file:${source.absolutePath}
            lcount:10,1,0
            """.trimIndent(),
        )
        val suite = registeredEngine().createCoverageSuite(
            "XMake Coverage",
            project,
            XMakeCoverageRunner(),
            directoryProvider(dataRoot),
            System.currentTimeMillis(),
        )

        CoverageDataManager.getInstance(project).coverageGathered(suite)

        val current = CoverageDataManager.getInstance(project).currentSuitesBundle
        assertNotNull("current suites bundle after coverageGathered", current)
        assertSame(suite, current!!.suites.single())
    }

    fun testSuiteForRunPointsAtDataRoot() {
        val dataRoot = dataRoot()
        val engine = registeredEngine()
        val enabled = engine.createCoverageEnabledConfiguration(
            runConfiguration(),
        ) as XMakeCoverageEnabledConfiguration

        val suite = enabled.createSuiteForRun(dataRoot, null)

        assertSame(engine, suite.coverageEngine)
        assertEquals(dataRoot.path, suite.coverageDataFileProvider.coverageDataFilePath)
    }

    fun testPainterHighlightsTheLinesOfAnOpenEditor() {
        // A real file on disk: the painter resolves editors through the local file system.
        val root = dataRoot()
        val sourceIo = File(root, "src/main.cpp").apply {
            parentFile.mkdirs()
            writeText("int main() {\n  return 0;\n}\n")
        }
        val report = File(root, "main.cpp.gcda.gcov").apply {
            writeText(
                """
                version:8.3.0
                file:src/main.cpp
                lcount:1,1
                lcount:2,0
                """.trimIndent(),
            )
        }
        val data = GcovReportParser.parse(report, root.toPath()).projectData
        val source = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceIo)
            ?: error("the fixture source never appeared in the VFS")
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, source), true)
            ?: error("no editor opened for the fixture source")

        val painter = project.getService(XMakeCoverageEditorPainter::class.java)
        painter.paint(editor, data)

        /** One highlighter per line the report counted: line 1 covered, line 2 not. */
        val highlighters = editor.markupModel.allHighlighters
        assertEquals(2, highlighters.size)
        val coveredLines = highlighters
            .map { highlighter -> editor.document.getLineNumber(highlighter.startOffset) + 1 }
            .sorted()
        assertEquals(listOf(1, 2), coveredLines)

        /** Clearing removes them again, the way switching coverage off does. */
        painter.clear(editor)
        assertEquals(0, editor.markupModel.allHighlighters.size)
    }

    fun testActivatingASuitePaintsTheOpenEditor() {
        // A real file on disk: the painter resolves editors through the local file system.
        val root = dataRoot()
        val sourceIo = File(root, "src/main.cpp").apply {
            parentFile.mkdirs()
            writeText("int main() {\n  return 0;\n}\n")
        }
        val source = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceIo)
            ?: error("the fixture source never appeared in the VFS")
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, source), true)
            ?: error("no editor opened for the fixture source")
        // The recorded path the way a local build records it: absolute.
        File(root, "main.cpp.gcda.gcov").apply {
            writeText(
                """
                version:8.3.0
                file:${sourceIo.absolutePath.replace(92.toChar(), '/')}
                lcount:1,1
                lcount:2,0
                """.trimIndent(),
            )
        }

        // The real activation flow: the suite becomes current, its data loads, and the painter
        // answers the suite events without anyone calling it directly. A suite that earlier
        // tests left active is closed first, so the manager does not ask about switching.
        val manager = CoverageDataManager.getInstance(project)
        manager.currentSuitesBundle?.let(manager::closeSuitesBundle)
        val suite = registeredEngine().createCoverageSuite(
            "XMake Coverage",
            project,
            XMakeCoverageRunner(),
            directoryProvider(root),
            System.currentTimeMillis(),
        )
        manager.coverageGathered(suite)
        awaitPaintedLines(editor, 2)
        assertEquals(2, editor.markupModel.allHighlighters.size)
    }

    /** Waits until the painter has put [expected] highlighters into the editor; the renew that
     *  loads the data runs in the background, so the events arrive after a pause. */
    private fun awaitPaintedLines(editor: com.intellij.openapi.editor.Editor, expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (editor.markupModel.allHighlighters.size < expected && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    private fun runConfiguration(): com.intellij.execution.configurations.RunConfigurationBase<*> =
        XMakeRunConfiguration(
            project,
            "coverage test",
            XMakeRunConfigurationType.getInstance().factory,
        )

    private fun registeredEngine(): XMakeCoverageEngine =
        CoverageEngine.EP_NAME.extensionList.filterIsInstance<XMakeCoverageEngine>().single()

    private fun dataRoot(): File =
        Files.createTempDirectory("xmake-coverage-test").toFile().also(tempRoots::add)

    private fun directoryProvider(root: File): CoverageFileProvider = object : CoverageFileProvider {
        override fun getCoverageDataFilePath(): String = root.path

        override fun ensureFileExists(): Boolean = root.isDirectory

        override fun isValid(): Boolean = root.isDirectory
    }
}
