package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): attacks on the user-presence path (plan 4.10, 5d) of
 * [IosSecureKeyValueStorage]: gets that wait on sheets at the same time, a cancel that races the call's own
 * completion, and a Keychain call that throws while a session is open.
 *
 * Contract (KDoc of [IosUserPresence] and of `IosSecureKeyValueStorage.onNative`; 05-platform-notes.md "User
 * presence"): every get opens its own session (the sheet shows on every get), no lock is held while a sheet is up,
 * every session is invalidated on every exit, and cancelling the waiting coroutine dismisses its sheet.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)
class IosUserPresenceBreakerR1Test {

    private val presence: IosSecureStorageOptions = IosSecureStorageOptions(
        userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_ANY, "Unlock"),
    )

    @Test
    fun `gets that wait on sheets at the same time all proceed and every session is released`() =
        runTest(timeout = 120.seconds) {
            // The Keychain call of each get blocks until ALL of them are inside the Keychain at once (the gate opens
            // on the last arrival). If the store serialized prompts behind a lock or a single thread, the last get
            // would never arrive and the test would end at its hang bound.
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("k", "secret"))
            val sessions = CountingAuthenticationSessions()
            val arrivals = AtomicInt(0)
            val allInside = CompletableDeferred<Unit>()
            keychain.script = { operation: String, _: Map<String, String> ->
                if (operation == "readData") {
                    if (arrivals.incrementAndFetch() == GETS) allInside.complete(Unit)
                    runBlocking { allInside.await() }
                }
                null
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.IO)

            val reads: List<Deferred<StorageResult<String?>>> = List(GETS) { async { store.get("k") } }
            val results: List<StorageResult<String?>> = reads.awaitAll()

            assertEquals(List(GETS) { StorageResult.Success("secret") }, results)
            assertEquals(GETS, sessions.opened, "one session per get")
            assertEquals(GETS, sessions.invalidated, "every session released")
            assertTrue(sessions.prompts.all { it == "Unlock" })
        }

    @Test
    fun `a cancel that races the get's own completion releases the session exactly once`() =
        runTest(timeout = 120.seconds) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("k", "secret"))
            val sessions = CountingAuthenticationSessions()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            keychain.script = { operation: String, _: Map<String, String> ->
                if (operation == "readData") {
                    reached.complete(Unit)
                    runBlocking { release.await() }
                }
                null
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)

            val waiting: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.get("k") }
            reached.await()
            assertEquals(1, sessions.opened, "control: the session is open while the call waits")
            assertEquals(0, sessions.invalidated, "control: not released yet")
            // The call returns its success at the same moment the caller is cancelled.
            release.complete(Unit)
            waiting.cancel()
            waiting.join()

            assertEquals(1, sessions.opened)
            assertEquals(1, sessions.invalidated, "released once, whichever of cancel and completion won")
            assertEquals(1, keychain.items.size, "a get changes nothing")
        }

    @Test
    fun `a Keychain call that throws while a session is open still releases the session`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "secret"))
        val sessions = CountingAuthenticationSessions()
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "readData" || operation == "update") throw IllegalStateException("seam failure") else null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

        val read: Result<StorageResult<String?>> = runCatching { store.get("k") }
        val write: Result<StorageResult<Unit>> = runCatching { store.put("k", "v") }

        assertTrue(read.getOrNull() !is StorageResult.Success, "a throwing read cannot be a Success: $read")
        assertTrue(write.getOrNull() !is StorageResult.Success, "a throwing write cannot be a Success: $write")
        assertEquals(2, sessions.opened, "control: both operations opened a session")
        assertEquals(2, sessions.invalidated, "a throwing Keychain call leaked its session")

        keychain.script = null
        assertEquals(StorageResult.Success("secret"), store.get("k"), "the store works afterwards")
        assertEquals(sessions.opened, sessions.invalidated)
    }

    private companion object {
        const val GETS: Int = 6
    }
}
