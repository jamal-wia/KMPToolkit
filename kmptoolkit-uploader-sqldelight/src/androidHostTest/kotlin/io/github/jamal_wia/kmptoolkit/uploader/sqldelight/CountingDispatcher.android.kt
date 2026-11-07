package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Runnable

private class CountingExecutorDispatcher(
    private val inner: ExecutorCoroutineDispatcher,
) : ExecutorCoroutineDispatcher() {
    var closes: Int = 0

    override val executor: Executor get() = inner.executor

    override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(context, block)

    override fun close() {
        closes++
        inner.close()
    }
}

internal actual fun countingDispatcher(): CountingDispatcher {
    val counting = CountingExecutorDispatcher(
        createConfinedDatabaseDispatcher("kmptoolkit-uploader-db-test"),
    )
    return object : CountingDispatcher {
        override val dispatcher: CloseableCoroutineDispatcher = counting
        override val closeCount: Int get() = counting.closes
    }
}
