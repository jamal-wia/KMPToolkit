package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.testing.MutableUploaderClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Shared fixtures of the A1 boss round 2 SQLite checks. */
internal object BossRound2Sqlite {

    const val MARKER2: String = "﷐kmp2﷑"

    suspend fun <T> real(millis: Long, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(millis) { block() } }

    /** A plain handler that records every payload it was asked to deliver. */
    class Counting(override val type: String = MARKER2 + "boss2.plain") : UploaderHandler<String> {
        val runs: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
        override val schemaVersion: Int = 1
        override val retryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            runs.update { it + payload }
            return AttemptResult.Success
        }
    }

    /** The real store, with the next [failures] confirming deletes failing as a locked database would. */
    class FlakyDelete(private val inner: UploaderStore, var failures: Int) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                throw UploaderStorageException(UploaderStorageOperation.DELETE_BY_ID, RuntimeException("database is locked"))
            }
            inner.deleteById(id)
        }
    }

    fun engine(
        store: UploaderStore,
        storage: UploaderStorage,
        handler: UploaderHandler<*>,
        scope: CoroutineScope,
        clock: MutableUploaderClock,
        ids: Iterator<String>,
    ): UploaderEngine = createUploaderEngine(
        store = store,
        handlers = listOf(handler),
        scope = scope,
        transactionRunner = storage.transactionRunner,
        clock = clock,
        idGenerator = { ids.next() },
    )

    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
