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
package io.xmake.debug.clion.perf

import com.intellij.profiler.CollapsedProfilerDumpFileParserBase
import com.intellij.profiler.api.BaseCallStackElement
import com.intellij.profiler.api.ProfilerDumpFileParsingResult
import com.intellij.profiler.api.ProfilerDumpFileParser
import com.intellij.profiler.api.ProfilerDumpParserProvider
import com.intellij.profiler.CollapsedDumpParser
import com.intellij.profiler.DummyCallTreeBuilder
import com.intellij.profiler.api.ValueMetric
import com.intellij.profiler.model.ThreadInfo
import com.intellij.profiler.ui.BaseCallStackElementRenderer
import com.intellij.openapi.project.Project

/**
 * Registers the collapsed-stacks format runtool.lua folds perf recordings into, so the
 * platform opens its own flame chart for a `perf record` run in any IDE — the same view
 * the native profilers show. Without a provider for this format, `ProfilerDumpDescriptor.open`
 * finds no parser and the view degrades to a notification naming the report command.
 *
 * A collapsed line is `frame;frame;frame count` — the folded output of `perf script` —
 * with the frames ordered leaf-first. Each line lands as one stack of one sample thread.
 */
internal class XMakePerfDumpParserProvider : ProfilerDumpParserProvider {

    override val id: String = "XMakePerfCollapsedStacks"

    override val name: String = "XMake perf (collapsed stacks)"

    override val requiredFileExtension: String = "collapsed"

    override fun createParser(project: Project): ProfilerDumpFileParser =
        XMakeCollapsedStacksParser(project)
}

/** Parses the collapsed lines into the platform's call tree, one frame element per
 *  `;`-separated segment; navigation resolves through the C/C++ symbols the frames carry. */
private class XMakeCollapsedStacksParser(project: Project) :
    CollapsedProfilerDumpFileParserBase(project) {

    override val helpId: String? = null

    override fun createStackElementRenderer(): BaseCallStackElementRenderer = Renderer()

    override fun createCollapsedParser(project: Project): CollapsedDumpParser<BaseCallStackElement> {
        val builder = DummyCallTreeBuilder<BaseCallStackElement>()
        return CollapsedDumpParser(
            builder,
            { PerfThread },
            { frameLine -> PerfStackElement(frameLine) },
            { line -> line.isNotBlank() && ' ' in line },
        )
    }

    private class Renderer : BaseCallStackElementRenderer() {

        override fun getText(element: BaseCallStackElement): String = element.fullName()

        override fun getCommentInfo(element: BaseCallStackElement): String? = null
    }
}

/** The single implicit thread of a collapsed recording. */
private object PerfThread : ThreadInfo {

    override val nativeId: String = "0"

    override val name: String = "perf samples"
}

/** One frame of a collapsed stack: the `class::method`-like symbol text perf folded. */
private class PerfStackElement(private val symbol: String) : BaseCallStackElement() {

    override fun fullName(): String = symbol

}


