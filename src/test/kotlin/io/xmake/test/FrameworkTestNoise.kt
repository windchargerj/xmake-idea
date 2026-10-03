package io.xmake.test

import com.intellij.openapi.application.AccessToken
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.LoggedErrorProcessor.Action
import java.util.EnumSet

/**
 * CLion test processes run Rider RD listeners (for example `VfsWatchRootHost`) whose asynchronous
 * callbacks may hit a light test project right after its temporary disposal, logging an
 * `AlreadyDisposedException` as an error. The platform test framework turns such logged errors
 * into test failures although they are unrelated to the code under test. The token returned by
 * [suppressDisposalNoise] drops exactly that noise while it is active; every other error is
 * processed normally.
 */
internal object FrameworkTestNoise {
    fun suppressDisposalNoise(): AccessToken =
        LoggedErrorProcessor.executeWith(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<String>,
                    t: Throwable?,
                ): Set<Action> =
                    if (generateSequence(t) { throwable -> throwable.cause }.any { it is AlreadyDisposedException }) {
                        EnumSet.noneOf(Action::class.java)
                    } else {
                        super.processError(category, message, details, t)
                    }
            },
        )
}
