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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class GcovReportParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses the text intermediate format`() {
        val source = sourceFile("src/main.cpp")
        val header = sourceFile("src/header.h")
        val report = reportFile(
            "main.cpp.gcda.gcov",
            """
            version:8.3.0
            file:src/main.cpp
            function:10,12,1,main
            lcount:10,1,0
            lcount:11,0,1
            lcount:12,7,1
            file:src/header.h
            lcount:3,4,0
            """.trimIndent(),
        )

        val data = GcovReportParser.parse(report, tempFolder.root.toPath()).projectData

        val sourceData = data.getClassData(XMakeCoveragePaths.lookupKey(source.toPath()))
        assertNotNull(sourceData)
        assertEquals(1, sourceData.getLineData(10).hits)
        assertEquals(0, sourceData.getLineData(11).hits)
        assertEquals(7, sourceData.getLineData(12).hits)
        assertNull(sourceData.getLineData(13))

        val headerData = data.getClassData(XMakeCoveragePaths.lookupKey(header.toPath()))
        assertNotNull(headerData)
        assertEquals(4, headerData.getLineData(3).hits)
    }

    @Test
    fun `accumulates repeated file sections`() {
        val source = sourceFile("src/main.cpp")
        val report = reportFile(
            "main.cpp.gcda.gcov",
            """
            file:src/main.cpp
            lcount:10,2
            file:src/main.cpp
            lcount:10,3
            lcount:11,1
            """.trimIndent(),
        )

        val data = GcovReportParser.parse(report, tempFolder.root.toPath()).projectData

        val sourceData = data.getClassData(XMakeCoveragePaths.lookupKey(source.toPath()))
        assertNotNull(sourceData)
        assertEquals(5, sourceData.getLineData(10).hits)
        assertEquals(1, sourceData.getLineData(11).hits)
    }

    @Test
    fun `parses the json format and resolves paths against its working directory`() {
        val source = sourceFile("src/main.cpp")
        val report = reportFile(
            "main.cpp.gcda.gcov.json",
            """
            {"format_version":"1","gcc_version":"11.2.0",
             "current_working_directory":"${jsonPath(tempFolder.root)}",
             "files":[{"file":"src/main.cpp","lines":[
                {"line_number":10,"count":2,"unexecuted_block":false},
                {"line_number":12,"count":0,"unexecuted_block":true}]}]}
            """.trimIndent(),
        )

        // The report's own working directory must win over the argument.
        val data = GcovReportParser.parse(report, File(tempFolder.root, "elsewhere").toPath()).projectData

        val sourceData = data.getClassData(XMakeCoveragePaths.lookupKey(source.toPath()))
        assertNotNull(sourceData)
        assertEquals(2, sourceData.getLineData(10).hits)
        assertEquals(0, sourceData.getLineData(12).hits)
    }

    @Test
    fun `parses gzipped json reports`() {
        val source = sourceFile("src/main.cpp")
        val report = File(tempFolder.root, "main.cpp.gcda.gcov.json.gz")
        val json = """
            {"format_version":"1","gcc_version":"12.1.0",
             "current_working_directory":"${jsonPath(tempFolder.root)}",
             "files":[{"file":"src/main.cpp","lines":[{"line_number":10,"count":9}]}]}
        """.trimIndent()
        GZIPOutputStream(report.outputStream()).bufferedWriter().use { writer -> writer.write(json) }

        val data = GcovReportParser.parse(report, null).projectData

        val sourceData = data.getClassData(XMakeCoveragePaths.lookupKey(source.toPath()))
        assertNotNull(sourceData)
        assertEquals(9, sourceData.getLineData(10).hits)
    }

    @Test
    fun `collects branch counters from json reports`() {
        val source = sourceFile("src/main.cpp")
        val report = reportFile(
            "main.cpp.gcda.gcov.json",
            """
            {"format_version":"1","gcc_version":"13.2.0",
             "current_working_directory":"${jsonPath(tempFolder.root)}",
             "files":[{"file":"src/main.cpp","lines":[
                {"line_number":10,"count":2,"branches":[
                    {"count":1,"fallthrough":true,"throw":false},
                    {"count":0,"fallthrough":false,"throw":false}]},
                {"line_number":12,"count":0,"branches":[{"count":0,"fallthrough":true,"throw":false}]},
                {"line_number":14,"count":5}]}]}
            """.trimIndent(),
        )

        val parsed = GcovReportParser.parse(report, null)

        val branchCoverage = parsed.branchCoverage[XMakeCoveragePaths.lookupKey(source.toPath())]
        assertNotNull(branchCoverage)
        assertEquals(3, branchCoverage!!.total)
        assertEquals(1, branchCoverage.covered)
    }

    @Test
    fun `reports no branch counters for the text intermediate format`() {
        val source = sourceFile("src/main.cpp")
        val report = reportFile(
            "main.cpp.gcda.gcov",
            """
            file:src/main.cpp
            lcount:10,1
            """.trimIndent(),
        )

        val parsed = GcovReportParser.parse(report, tempFolder.root.toPath())

        assertNull(parsed.branchCoverage[XMakeCoveragePaths.lookupKey(source.toPath())])
    }

    @Test
    fun `maps the linux working directory of a wsl report onto the drive`() {
        // The WSL path mapping maps /mnt/<drive> onto Windows drive paths, which only
        // resolves where those exist; the CI's Linux runners have no drive letters.
        assumeTrue("WSL path mapping needs Windows", SystemInfo.isWindows)
        val report = reportFile(
            "main.cpp.gcda.gcov.json",
            """
            {"format_version":"1","gcc_version":"13.2.0",
             "current_working_directory":"/mnt/e/proj",
             "files":[{"file":"src/main.cpp","lines":[{"line_number":10,"count":4}]}]}
            """.trimIndent(),
        )
        // The resolver of a WSL distribution: /mnt/<drive> is the drive itself.
        val data = GcovReportParser.parse(report, null) { raw ->
            if (raw.startsWith("/mnt/")) Path.of(raw.replaceFirst("/mnt/([a-zA-Z])/".toRegex(), "$1:/"))
            else null
        }.projectData

        val expected = XMakeCoveragePaths.lookupKey(Path.of("E:\\proj\\src\\main.cpp"))
        val sourceData = data.getClassData(expected)
        assertNotNull(
            "the data must register under the drive form of the path (expected=$expected, registered=${data.classes.keys})",
            sourceData,
        )
        assertEquals(4, sourceData!!.getLineData(10).hits)
    }

    private fun sourceFile(relativePath: String): File {
        val file = File(tempFolder.root, relativePath)
        file.parentFile.mkdirs()
        file.writeText("// fixture\n")
        return file
    }

    private fun reportFile(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    private fun jsonPath(file: File): String = file.absolutePath.replace('\\', '/')
}
