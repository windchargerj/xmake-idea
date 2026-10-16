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
package io.xmake.run.perf

import com.intellij.profiler.ProfilerDumpParserProviderFinder
import com.intellij.profiler.api.getProfilerDumpParserProviders
import io.xmake.XMakePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * The profiler view opens a recording through the platform's dump parser lookup. This asserts
 * the lookup answers for the folded stacks this plugin writes, in the CLion the plugin actually
 * runs on: the native dump parser is registered with the `collapsed` extension, so the flame
 * graph has a parser the moment the folded file exists.
 */
class XMakePerfDumpParserTest : XMakePlatformTestCase() {

    fun testCollapsedStacksFindTheirParser() {
        val collapsed = Files.createTempFile("xmake-perf", ".collapsed").toFile().apply {
            writeText("main;compute 1\nmain 2\n")
        }
        try {
            val result = ProfilerDumpParserProviderFinder()
                .findParserProvider(collapsed, getProfilerDumpParserProviders())

            assertTrue(
                "the native collapsed-stacks parser must answer for a .collapsed file, got $result",
                result is ProfilerDumpParserProviderFinder.Result.Success,
            )
        } finally {
            collapsed.delete()
        }
    }
}
