package io.github.jamal_wia.kmptoolkit.storage

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 1 of group sto-api (STO-2, plan 4.10 "ensureKey() is non-mutating", D-15..D-17, ticket
 * release-5-1-0): the provisional iOS `ensureKey` attacked through what a Kotlin/Native test executable
 * can reach — no Keychain, so every `SecItem*` call answers `errSecNotAvailable` (see
 * [IosSecureKeyValueStorageTest]) — and the status mapping it reports with.
 *
 * Contract: `EncryptedKeyValueStorage.ensureKey` KDoc (typed failure, nothing throws), every ensureKey
 * failure is reported for `StorageOperation.ENSURE_KEY` with `key = null` (COMMON D-15..D-17), a cancelled
 * caller gets `CancellationException` (KeyValueStorage KDoc). The real-time bound is a hang bound only.
 */
@OptIn(ExperimentalAtomicApi::class)
class IosSecureKeyValueStorageBreakerR1Test {

    private val storage: SecureKeyValueStorage =
        createSecureKeyValueStorage(StorageConfig("io.github.jamal_wia.kmptoolkit.storage.breaker.r1"))

    @Test
    fun `ensureKey of an already cancelled caller throws CancellationException rather than answering`() = runTest {
        var outcome: StorageResult<Unit>? = null
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            try {
                outcome = storage.ensureKey()
            } catch (cancellation: CancellationException) {
                thrown = cancellation
                throw cancellation
            }
        }
        job.join()

        assertTrue(thrown is CancellationException, "was $thrown")
        assertNull(outcome, "a cancelled ensureKey reported $outcome")
    }

    @Test
    fun `64 concurrent ensureKey calls on threads all answer the same typed failure and none throws`() = runTest(timeout = 120.seconds) {
        val answers: AtomicInt = AtomicInt(0)
        val wrong: AtomicInt = AtomicInt(0)
        withContext(Dispatchers.Default) {
            withTimeout(90.seconds) {
                coroutineScope {
                    repeat(64) {
                        launch {
                            repeat(20) {
                                val result: StorageResult<Unit> = storage.ensureKey()
                                if (result.errorOrNull() == StorageError.Unavailable()) answers.addAndFetch(1) else wrong.addAndFetch(1)
                            }
                        }
                    }
                }
            }
        }

        assertEquals(0, wrong.load(), "some ensureKey calls answered something other than Unavailable")
        assertEquals(64 * 20, answers.load())
    }

    @Test
    fun `ensureKey interleaved with every other operation keeps answering typed failures`() = runTest {
        repeat(50) { round: Int ->
            assertEquals(StorageError.Unavailable(), storage.ensureKey().errorOrNull(), "round $round ensureKey")
            assertEquals(StorageError.Unavailable(), storage.put("k$round", "v").errorOrNull(), "round $round put")
            assertEquals(StorageError.Unavailable(), storage.get("k$round").errorOrNull(), "round $round get")
            assertEquals(StorageError.Unavailable(), storage.remove("k$round").errorOrNull(), "round $round remove")
        }
        assertEquals(StorageError.Unavailable(), storage.clear().errorOrNull())
        assertEquals(StorageError.Unavailable(), storage.ensureKey().errorOrNull())
    }

    @Test
    fun `every status ensureKey can meet is reported for ENSURE_KEY and never names a key`() {
        val statuses: List<Int> = listOf(
            ERR_SEC_DECODE, ERR_SEC_PARAM, -34018, -128, -25293, -25299, -25300, -4, 1, Int.MIN_VALUE, Int.MAX_VALUE,
        )
        for (status: Int in statuses) {
            val error: StorageError = status.toStorageError(StorageOperation.ENSURE_KEY, key = null)
            when (error) {
                is StorageError.OperationFailed -> {
                    assertEquals(StorageOperation.ENSURE_KEY, error.operation, "status $status")
                    assertNull(error.key, "status $status named a key")
                    assertEquals(status, error.platformCode, "status $status lost its raw code")
                }
                is StorageError.Undecryptable -> assertNull(error.key, "status $status named a key")
                is StorageError.Unavailable, is StorageError.AccessDenied, is StorageError.Unsupported -> Unit
                is StorageError.InvalidValue -> throw AssertionError("status $status became InvalidValue, which names a key")
            }
        }
        assertEquals(StorageError.Unavailable(), ERR_SEC_NOT_AVAILABLE.toStorageError(StorageOperation.ENSURE_KEY, null))
        assertEquals(StorageError.Unavailable(), ERR_SEC_INTERACTION_NOT_ALLOWED.toStorageError(StorageOperation.ENSURE_KEY, null))
    }
}
