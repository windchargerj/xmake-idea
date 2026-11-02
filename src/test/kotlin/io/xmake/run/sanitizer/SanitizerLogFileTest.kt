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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SanitizerLogFileTest {

    @get:Rule
    val temporaryDirectory = TemporaryFolder()

    @Test
    fun `the log_path option quotes the whole forward-slashed path`() {
        val option = logPathOption(Path.of("E:", "proj", ".xmake-analysis", "address.%p.log"))

        assertEquals("log_path=\"E:/proj/.xmake-analysis/address.%p.log\"", option)
    }

    @Test
    fun `reading joins the run's mirror files in name order and deletes only those`() {
        val directory = temporaryDirectory.newFolder().toPath()
        Files.writeString(directory.resolve("address.10.log"), "report of pid 10")
        Files.writeString(directory.resolve("address.9.log"), "report of pid 9")
        Files.writeString(directory.resolve("thread.10.log"), "report of another runtime")
        Files.writeString(directory.resolve("address.notes.txt"), "not a mirror file")

        val collected = readSanitizerLogFiles(directory, SanitizerKind.ADDRESS)

        assertEquals("report of pid 10\nreport of pid 9", collected)
        assertFalse(Files.exists(directory.resolve("address.10.log")))
        assertFalse(Files.exists(directory.resolve("address.9.log")))
        assertTrue(Files.exists(directory.resolve("thread.10.log")))
        assertTrue(Files.exists(directory.resolve("address.notes.txt")))
        assertTrue(Files.isDirectory(directory))
    }

    @Test
    fun `reading an empty directory collects nothing`() {
        val directory = temporaryDirectory.newFolder().toPath()

        assertEquals("", readSanitizerLogFiles(directory, SanitizerKind.THREAD))
    }
}
