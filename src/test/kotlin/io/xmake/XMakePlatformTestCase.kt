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
package io.xmake

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.EnumSet

/**
 * Base for tests that need the platform test fixture.
 *
 * The platform compares [CodeInsightSettings] with a blank instance at teardown and reports any
 * difference as damage. In the CLion test environment `AUTO_POPUP_JAVADOC_INFO` is already true
 * before a test runs, so every fixture test would report damage regardless of what it does;
 * restoring the field keeps the check meaningful for everything else.
 *
 * The CLion 2026.2 container runs the RD client (the Code With Me protocol stack), whose
 * dispatcher delivers queued messages on the EDT after the fixture project they belong to has
 * been disposed; the container logs that as an error, and the test logger rethrows logged errors
 * as failures of whichever test runs when the delivery lands. That is a platform teardown race,
 * not something the tests under this repo control - the processor lets those two signatures log
 * and print without failing the test, while every other logged error still rethrows.
 */
abstract class XMakePlatformTestCase : BasePlatformTestCase() {

    private var loggedErrorToken: AccessToken? = null

    override fun setUp() {
        super.setUp()
        loggedErrorToken = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(
                message: String,
                category: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): MutableSet<LoggedErrorProcessor.Action> {
                // The noise identifies itself two ways: the logger's category names the
                // RD client, and the logged line names a container that is already
                // disposed. Either matches - the dispatcher's payload varies by which
                // VFS listener the delivery reached.
                // The raw logged line (what lands in system-err) opens with
                // "ERROR: Catch <the exception> <its message>" - the guard matches that
                // shape as well as the exception chain, since the dispatcher's payload
                // varies by which VFS listener the delivery reached.
                val messageAndCause = buildString {
                    append(message)
                    var cause: Throwable? = throwable
                    while (cause != null) {
                        append(' ').append(cause.message).append(' ').append(cause.javaClass.name)
                        cause = cause.cause
                    }
                    throwable?.stackTrace?.take(6)?.forEach { f -> append(' ').append(f.className) }
                }
                // The "Catch ... AlreadyDisposedException" opening of the raw line is
                // the form the dispatcher's error log takes in system-err.
                val platformTeardownNoise = messageAndCause.contains("rdclient", ignoreCase = true) ||
                    messageAndCause.contains("AlreadyDisposedException") ||
                    messageAndCause.contains("already disposed", ignoreCase = true) ||
                    (category.startsWith("com.jetbrains.rider") && messageAndCause.contains("disposed", ignoreCase = true))
                return if (platformTeardownNoise) {
                    EnumSet.of(LoggedErrorProcessor.Action.LOG, LoggedErrorProcessor.Action.STDERR)
                } else {
                    LoggedErrorProcessor.Action.ALL
                }
            }
        })
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
            loggedErrorToken?.finish()
            loggedErrorToken = null
        } finally {
            super.tearDown()
        }
    }
}
