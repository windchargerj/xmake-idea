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
package io.xmake.run.tidy

import io.xmake.run.analysis.AnalysisSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClangTidyReportParserTest {

    @Test
    fun `parses findings with checks, notes without them, and merges repeats`() {
        val parser = ClangTidyReportParser()
        parser.feed(
            """
            checking for clang-tidy ...
            src/main.cpp:13:9: warning: use nullptr for constant null [modernize-use-nullptr]
            src/main.cpp:13:9: note: nullptr fixes are available with -fix
            src/util.cpp:41:3: error: cast from pointer to smaller type [bugprone-sizeof-expression]
            src/main.cpp:13:9: warning: use nullptr for constant null [modernize-use-nullptr]
            2 warnings and 1 error generated.
            """.trimIndent() + "\n",
        )
        val report = parser.finish("demo")

        assertEquals(3, report.issues.size)
        val nullptr = report.issues.first { it.type == "modernize-use-nullptr" }
        assertEquals(2, nullptr.count)
        assertEquals(AnalysisSeverity.WARNING, nullptr.severity)
        val frame = nullptr.primaryFrame!!
        assertEquals("src/main.cpp", frame.file)
        assertEquals(13, frame.line)
        assertEquals(9, frame.column)
        val cast = report.issues.first { it.type == "bugprone-sizeof-expression" }
        assertEquals(AnalysisSeverity.ERROR, cast.severity)
        val note = report.issues.first { it.type == "note" }
        assertEquals(AnalysisSeverity.NOTE, note.severity)
        assertTrue(report.unparsed.any { it.startsWith("checking") })
        assertTrue(report.unparsed.any { it.endsWith("generated.") })
        assertEquals("clang-tidy", report.source)
    }

    @Test
    fun `parses windows paths and column-less lines`() {
        val parser = ClangTidyReportParser()
        parser.feed("E:\\proj\\src\\a.cpp:7:5: warning: unused variable 'x' [-Wunused-variable]\n")
        parser.feed("/wsl/proj/src/b.cpp:20: warning: something old [misc]\n")
        val report = parser.finish("demo")

        assertEquals(2, report.issues.size)
        val windows = report.issues.first { it.primaryFrame?.file?.contains('a') == true }
        assertEquals("E:\\proj\\src\\a.cpp", windows.primaryFrame?.file)
        assertEquals(7, windows.primaryFrame?.line)
        val old = report.issues.first { it.primaryFrame?.file?.contains('b') == true }
        assertEquals(20, old.primaryFrame?.line)
    }

    @Test
    fun `a clean project reports nothing`() {
        val parser = ClangTidyReportParser()
        parser.feed("checking for clang-tidy ...\n")
        val report = parser.finish("demo")

        assertEquals(0, report.issues.size)
        assertEquals(0, report.totalFindings)
    }
}
