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
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base for tests that need the platform test fixture.
 *
 * The platform compares [CodeInsightSettings] with a blank instance at teardown and reports any
 * difference as damage. In the CLion test environment `AUTO_POPUP_JAVADOC_INFO` is already true
 * before a test runs, so every fixture test would report damage regardless of what it does;
 * restoring the field keeps the check meaningful for everything else.
 */
abstract class XMakePlatformTestCase : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
        } finally {
            super.tearDown()
        }
    }
}
