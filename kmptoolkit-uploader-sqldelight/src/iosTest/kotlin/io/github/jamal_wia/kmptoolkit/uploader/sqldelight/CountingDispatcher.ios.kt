package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Runnable

private class CountingNativeDispatcher(
    private val inner: CloseableCoroutineDispatcher,
) : CloseableCoroutineDispatcher() {
    var closes: Int = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(context, block)

    override fun close() {
        closes++
        inner.close()
    }
}

internal actual fun countingDispatcher(): CountingDispatcher {
    val counting = CountingNativeDispatcher(createConfinedDatabaseDispatcher("kmptoolkit-uploader-db-test"))
    return object : CountingDispatcher {
        override val dispatcher: CloseableCoroutineDispatcher = counting
        override val closeCount: Int get() = counting.closes
    }
}
