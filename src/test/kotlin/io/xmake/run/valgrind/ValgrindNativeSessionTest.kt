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

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ExecutionConsoleEx
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import io.xmake.XMakePlatformTestCase
import io.xmake.run.valgrind.openXMakeValgrindNativeSession
import io.xmake.run.valgrind.parseValgrindReport
import java.io.File
import java.nio.file.Files
import javax.swing.JTree
import javax.swing.tree.TreeNode

/**
 * The native results panel and the console wrapper live in CLion's memory profiling module and
 * are reached through the plugin's extension point, implemented by the optional CLion module.
 * This drives the whole path against the CLion the plugin actually runs on, through the same
 * extension lookup the runner uses, so a CLion release that moves the API fails here first.
 */
class ValgrindNativeSessionTest : XMakePlatformTestCase() {

    private val tempRoots = mutableListOf<File>()

    override fun tearDown() {
        try {
            tempRoots.forEach(FileUtil::delete)
            tempRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testOpensTheNativeSessionAndShowsTheReport() {
        val report = reportWithOneError()
        val errors = parseValgrindReport(report)
        assertEquals(1, errors.size)

        val session = openXMakeValgrindNativeSession(project)
        assertNotNull("the native session in a CLion test instance", session)
        session!!.addNewErrors(errors) { path -> path }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        /** Composes the run content the way the runner does: the console wrapped, then the
         *  platform builds the layout that hosts the console and the results panel. */
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val wrapper = session.wrapConsole(project, console)
        assertNotNull("the native console wrapper", wrapper)
        val ui = RunnerLayoutUi.Factory.getInstance(project)
            .create("xmake-valgrind-test", "test", "test", testRootDisposable)
        (wrapper as ExecutionConsoleEx).buildUi(ui)

        val contents = ui.contents.map { content -> content.displayName }
        assertContainsElements(contents, "Valgrind")

        /** The results tab carries the native panel, filled with the parsed error, and its tree
         *  shows the kind of that error as its root child. */
        val panel = ui.contents.first { content -> content.displayName == "Valgrind" }.component
        val tree = panel.javaClass.getMethod("getTree").invoke(panel) as JTree
        val root = tree.model.root as TreeNode
        assertEquals(1, root.childCount)
        assertEquals("InvalidRead", root.getChildAt(0).toString())

        /** Rendering a row runs the native cell renderer with the warning counts, and the CLion
         *  presentation extension that colors stack frames and picks the file icons. */
        tree.cellRenderer.getTreeCellRendererComponent(tree, root.getChildAt(0), false, false, true, 0, false)
    }

    fun testReadsAReportThatIsStillBeingWritten() {
        val report = File(newTempRoot(), "report.xml").apply {
            writeText(
                """
                <?xml version="1.0"?>
                <valgrindoutput>
                <error>
                  <kind>InvalidRead</kind>
                  <xwhat><text>Invalid read of size 4</text></xwhat>
                  <stack><frame><fn>main</fn></frame></stack>
                </error>
                <error>
                  <kind>Leak_DefinitelyLost</kind>
                  <xwhat><text>8 bytes in 1 blocks are definitely
                """.trimIndent(),
            )
        }

        val errors = parseValgrindReport(report)

        /** A report being written ends mid-element; the errors that are complete count. */
        assertEquals(1, errors.size)
        assertEquals("InvalidRead", errors.single().kind)
    }

    fun testKeepsFramesAndSuppression() {
        val report = File(newTempRoot(), "report.xml").apply {
            writeText(
                """
                <?xml version="1.0"?>
                <valgrindoutput>
                <error>
                  <kind>InvalidRead</kind>
                  <xwhat><text>Invalid read of size 4</text></xwhat>
                  <stack>
                    <frame>
                      <ip>0x1092A3</ip>
                      <obj>/tmp/build/hello</obj>
                      <fn>main</fn>
                      <dir>/tmp/build/src</dir>
                      <file>main.cpp</file>
                      <line>23</line>
                    </frame>
                  </stack>
                  <suppression>
                    <kind>Memcheck:Addr4</kind>
                    <rawtext><![CDATA[{<insert_a_suppression_name_here>}]]></rawtext>
                  </suppression>
                </error>
                </valgrindoutput>
                """.trimIndent(),
            )
        }

        val error = parseValgrindReport(report).single()

        assertEquals("InvalidRead", error.kind)
        val frame = error.descriptions.single().frames.single()
        assertEquals("0x1092A3", frame.instructionPointer)
        assertEquals("/tmp/build/hello", frame.binary)
        assertEquals("main", frame.function)
        assertEquals("/tmp/build/src", frame.directory)
        assertEquals("main.cpp", frame.file)
        assertEquals(23, frame.line)
        assertEquals("{<insert_a_suppression_name_here>}", error.suppression)
    }

    private fun reportWithOneError(): File = File(newTempRoot(), "report.xml").apply {
        writeText(
            """
            <?xml version="1.0"?>
            <valgrindoutput>
            <error>
              <kind>InvalidRead</kind>
              <xwhat><text>Invalid read of size 4</text></xwhat>
              <stack>
                <frame><fn>main</fn></frame>
              </stack>
            </error>
            </valgrindoutput>
            """.trimIndent(),
        )
    }

    private fun newTempRoot(): File =
        Files.createTempDirectory("xmake-valgrind-native").toFile().also(tempRoots::add)
}
