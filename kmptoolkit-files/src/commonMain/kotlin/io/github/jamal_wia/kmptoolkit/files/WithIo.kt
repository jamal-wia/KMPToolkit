package io.github.jamal_wia.kmptoolkit.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Runs [block] on [dispatcher] with a [CancellationCheck] that turns true once the calling coroutine is cancelled.
 * A cancelled caller gets `CancellationException` (the block's `Failure(Cancelled)` is never returned to it).
 *
 * @since 5.0.0
 */
public suspend fun <T> FileAccess.withIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: FileAccess.(cancellation: CancellationCheck) -> T,
): T {
    val access: FileAccess = this
    return withContext(dispatcher) {
        val job: kotlinx.coroutines.Job = coroutineContext.job
        access.block(CancellationCheck { !job.isActive })
    }
}
