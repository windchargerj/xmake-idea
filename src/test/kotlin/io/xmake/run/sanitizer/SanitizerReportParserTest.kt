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
package io.xmake.run.sanitizer

import io.xmake.run.analysis.AnalysisSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizerReportParserTest {

    @Test
    fun `parses an addresssanitizer heap-buffer-overflow with its allocation stack`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        parser.feed(
            """
            program says hello
            ==4254==ERROR: AddressSanitizer: heap-buffer-overflow on address 0x602000000034 at pc 0x55e1fef5abcd
            READ of size 4 at 0x602000000034 thread T0
                #0 0x55e1fef5abcd in main /home/dev/proj/src/main.cpp:13:9
                #1 0x7f2a1c234567 in __libc_start_call_main (/lib/x86_64-linux-gnu/libc.so.6+0x2a1b8)
                #2 0x7f2a1c234c24 in __libc_start_main_impl (/lib/x86_64-linux-gnu/libc.so.6+0x2ac24)

            0x602000000034 is located 4 bytes to the right of 20-byte region [0x602000000020,0x602000000034)
            allocated by thread T0 here:
                #0 0x55e1fe91c1d2 in operator new[](unsigned long) (/usr/bin/proj+0x11bc1d2)
                #1 0x55e1fef5a9a1 in main /home/dev/proj/src/main.cpp:9:22

            SUMMARY: AddressSanitizer: heap-buffer-overflow /home/dev/proj/src/main.cpp:13 in main
            Shadow bytes around the buggy address:
              0x0c047fff7fc0: 00 00 00 00 00 00 00 00
            """.trimIndent(),
        )
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        val issue = report.issues[0]
        assertEquals("heap-buffer-overflow", issue.type)
        assertEquals(AnalysisSeverity.ERROR, issue.severity)
        assertEquals(2, issue.stacks.size)
        assertEquals("READ of size 4 at 0x602000000034 thread T0", issue.stacks[0].title)
        assertEquals("main", issue.stacks[0].frames[0].function)
        assertEquals("/home/dev/proj/src/main.cpp", issue.stacks[0].frames[0].file)
        assertEquals(13, issue.stacks[0].frames[0].line)
        assertEquals(9, issue.stacks[0].frames[0].column)
        val allocation = issue.stacks[1]
        assertTrue(allocation.title.contains("allocated by thread T0"))
        assertEquals("/home/dev/proj/src/main.cpp:9", "${allocation.frames[1].file}:${allocation.frames[1].line}")
        assertEquals("program says hello", report.unparsed.firstOrNull())
    }

    @Test
    fun `merges identical reports into one counted issue`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        repeat(3) {
            parser.feed(
                """
                ==1==ERROR: AddressSanitizer: heap-use-after-free on address 0x603000000010 at pc 0x55e100000001
                READ of size 1 at 0x603000000010 thread T0
                    #0 0x55e100000001 in use /home/dev/proj/src/use.cpp:7:5
                    #1 0x55e100000002 in main /home/dev/proj/src/main.cpp:20:1
                freed by thread T0 here:
                    #0 0x55e100000003 in free /home/dev/proj/src/use.cpp:4:1
                SUMMARY: AddressSanitizer: heap-use-after-free /home/dev/proj/src/use.cpp:7 in use
                """.trimIndent() + "\n",
            )
        }
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        assertEquals(3, report.issues[0].count)
        assertEquals(3, report.totalFindings)
    }

    @Test
    fun `parses leaksanitizer leak blocks as individual findings`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        parser.feed(
            """
            ==9==ERROR: LeakSanitizer: detected memory leaks

            Direct leak of 32 byte(s) in 1 object(s) allocated from:
                #0 0x55e1a1 in operator new(unsigned long) (/usr/bin/proj+0x11b1a1)
                #1 0x55e1b2 in make /home/dev/proj/src/leak.cpp:12:20

            Indirect leak of 8 byte(s) in 1 object(s) allocated from:
                #0 0x55e1c3 in strdup (/lib/x86_64-linux-gnu/libc.so.6+0x9c1c3)

            SUMMARY: AddressSanitizer: 40 byte(s) leaked in 2 allocation(s).
            """.trimIndent(),
        )
        val report = parser.finish("demo")

        assertEquals(2, report.issues.size)
        assertEquals("direct leak", report.issues[0].type)
        assertEquals(AnalysisSeverity.WARNING, report.issues[0].severity)
        assertEquals("indirect leak", report.issues[1].type)
        assertTrue(report.issues[1].stacks.first().frames.isNotEmpty())
    }

    @Test
    fun `parses threadsanitizer races with both stacks`() {
        val parser = SanitizerReportParser(SanitizerKind.THREAD)
        parser.feed(
            """
            ==================
            WARNING: ThreadSanitizer: data race (pid=26327)
              Write of size 4 at 0x7f89554701d0 by thread T1:
                #0 Thread1(void*) simple_race.cc:8 (exe+0x00000000a360)
              Previous write of size 4 at 0x7f89554701d0 by main thread:
                #0 main simple_race.cc:11 (exe+0x00000000a3b4)
              Location is global 'x' of size 4 at 0x7f89554701d0 (x+0x0)
              Thread T1 (tid=26328, running) created at:
                #0 pthread_create tsan_interceptors.cc:705 (exe+0x00000000c790)
                #1 main simple_race.cc:10
            SUMMARY: ThreadSanitizer: data race simple_race.cc:8 in Thread1
            ==================
            ThreadSanitizer: reported 1 warnings
            """.trimIndent(),
        )
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        val issue = report.issues[0]
        assertEquals("data race", issue.type)
        assertEquals(AnalysisSeverity.ERROR, issue.severity)
        assertTrue(issue.stacks[0].title.contains("Write of size 4"))
        assertEquals("Thread1(void*)", issue.stacks[0].frames[0].function)
        assertEquals("simple_race.cc", issue.stacks[0].frames[0].file)
        assertEquals(8, issue.stacks[0].frames[0].line)
        assertTrue(issue.stacks[1].title.contains("Previous write"))
        assertTrue(issue.stacks.any { it.title.contains("created at") })
        assertEquals("simple_race.cc:8", issue.primaryFrame!!.let { "${it.file}:${it.line}" })
    }

    @Test
    fun `parses memorysanitizer uninitialized values`() {
        val parser = SanitizerReportParser(SanitizerKind.MEMORY)
        parser.feed(
            """
            WARNING: MemorySanitizer: use-of-uninitialized-value
                #0 0x7f7893912f0b in main /home/dev/proj/src/umr2.cc:7:3
                #1 0x7f78938b676c in __libc_start_main libc-start.c:226

              Uninitialized value was stored to memory at
                #0 0x7f78938b5c25 in __msan_chain_origin msan.cc:484
                #1 0x7f7893912ecd in main /home/dev/proj/src/umr2.cc:6:9

              Uninitialized value was created by a heap allocation
                #0 0x7f7893901cbd in operator new[](unsigned long) msan_new_delete.cc:44
                #1 0x7f7893912e06 in main /home/dev/proj/src/umr2.cc:4:18

            SUMMARY: MemorySanitizer: use-of-uninitialized-value /home/dev/proj/src/umr2.cc:7 in main
            """.trimIndent(),
        )
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        assertEquals("use-of-uninitialized-value", report.issues[0].type)
        assertEquals(3, report.issues[0].stacks.size)
        assertEquals("umr2.cc:7", report.issues[0].primaryFrame!!.let { "${it.file?.substringAfterLast('/')}:" +
                "${it.line}" })
    }

    @Test
    fun `parses ubsan one-line runtime errors and merges repeats`() {
        val parser = SanitizerReportParser(SanitizerKind.UNDEFINED)
        parser.feed(
            """
            /home/dev/proj/src/math.cpp:8:15: runtime error: division by zero
            /home/dev/proj/src/math.cpp:8:15: runtime error: division by zero
            /home/dev/proj/src/parse.cpp:40:9: runtime error: member access within null pointer of type 'struct node'
            note: parse.cpp:40:9: runtime error: variable 'node' is null
            """.trimIndent(),
        )
        val report = parser.finish("demo")

        val divisions = report.issues.filter { it.message.contains("division by zero") }
        assertEquals(1, divisions.size)
        assertEquals(2, divisions[0].count)
        assertEquals(AnalysisSeverity.WARNING, divisions[0].severity)
        assertNotNull(divisions[0].primaryFrame)
        assertEquals("/home/dev/proj/src/math.cpp", divisions[0].primaryFrame!!.file)
        assertEquals(8, divisions[0].primaryFrame!!.line)
        assertTrue(report.issues.any { it.message.contains("null pointer") })
    }

    @Test
    fun `identical reports group by the runtime's dedup token`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        parser.feed(
            """
            ==1==ERROR: AddressSanitizer: heap-use-after-free on address 0x603000000010
            READ of size 1 at 0x603000000010 thread T0
                #0 0x55e100000001 in use /home/dev/proj/src/use.cpp:7:5
            DEDUP_TOKEN: use--main--__libc_start_main
            SUMMARY: AddressSanitizer: heap-use-after-free /home/dev/proj/src/use.cpp:7 in use
            """.trimIndent() + "\n",
        )
        // The same token with different printed lines still merges: the token, not the
        // frames, names the code the runtime blames.
        parser.feed(
            """
            ==2==ERROR: AddressSanitizer: heap-use-after-free on address 0x603000000099
            READ of size 1 at 0x603000000099 thread T0
                #0 0x55e100000042 in use /home/dev/proj/src/use.cpp:8:11
            DEDUP_TOKEN: use--main--__libc_start_main
            SUMMARY: AddressSanitizer: heap-use-after-free /home/dev/proj/src/use.cpp:8 in use
            """.trimIndent() + "\n",
        )
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        assertEquals(2, report.issues[0].count)
    }

    @Test
    fun `a plain run without findings reports nothing`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        parser.feed("hello\nworld\n")
        val report = parser.finish("demo")

        assertEquals(0, report.issues.size)
        assertEquals(0, report.totalFindings)
    }

    @Test
    fun `chunk boundaries never split a report`() {
        val parser = SanitizerReportParser(SanitizerKind.ADDRESS)
        val text =
            "==7==ERROR: AddressSanitizer: stack-overflow on address 0x7ffc (pc 0x55e1 sp 0x7ffc T0)\n" +
                    "    #0 0x55e1aa in recurse /home/dev/proj/src/deep.cpp:4:5\n" +
                    "    #1 0x55e1ab in recurse /home/dev/proj/src/deep.cpp:4:5\n" +
                    "SUMMARY: AddressSanitizer: stack-overflow /home/dev/proj/src/deep.cpp:4 in recurse\n"
        // feed in 7-character pieces: any split must reassemble into the same report
        var index = 0
        while (index < text.length) {
            parser.feed(text.substring(index, minOf(index + 7, text.length)))
            index += 7
        }
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        assertEquals(2, report.issues[0].stacks[0].frames.size)
        assertEquals("stack-overflow", report.issues[0].type)
    }

    @Test
    fun `carriage returns and ansi colors from a pty run are cleaned`() {
        val parser = SanitizerReportParser(SanitizerKind.UNDEFINED)
        parser.feed("[0m/home/dev/proj/src/one.cpp:3:5: runtime error: load of value 255 is out of range\r\n")
        val report = parser.finish("demo")

        assertEquals(1, report.issues.size)
        assertEquals("/home/dev/proj/src/one.cpp", report.issues[0].primaryFrame?.file)
    }
}
