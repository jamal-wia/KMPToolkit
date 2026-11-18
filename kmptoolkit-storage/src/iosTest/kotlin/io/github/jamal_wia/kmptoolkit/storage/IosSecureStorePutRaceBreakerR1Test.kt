package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): attacks on the update-then-add write of
 * [IosSecureKeyValueStorage.put] when other writers and removers of the same item race it.
 *
 * Contract (docs/kmptoolkit-storage/04-api-reference.md, `put`; the KDoc in `IosSecureKeyValueStorage.write`):
 * `put` overwrites, the value is readable once it returns, and racing writers leave the winner undefined but never
 * make one of them fail. Another writer or remover may be another process (an app extension), so no lock can
 * serialize them: the store has to converge by itself, and do so in a bounded number of Keychain calls.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorePutRaceBreakerR1Test {

    private fun storeQueries(): KeychainQueries = KeychainQueries(
        service = STORE_SERVICE,
        accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        accessGroup = null,
        synchronizable = false,
        userPresence = null,
    )

    /** Another remover deletes [key] directly in the Keychain, as an app extension would. */
    private fun InMemoryKeychainPort.removeBehindTheStore(key: String) {
        storeQueries().item(key, authentication = null).use { delete(it) }
    }

    @Test
    fun `a put whose lost add meets a remove before its retry still stores its value`() = runTest {
        // Schedule (one round of interference only): our update misses; another writer adds the item before our
        // add, so our add answers errSecDuplicateItem; another remover deletes it before our retried update. The
        // item is now absent, and an add would succeed: the put must not report a failure for a write that merely
        // raced two others. This is the schedule the executor's own storm (IosSecureStoreStormTest) hit at random
        // (OperationFailed(PUT, platformCode=-25300)).
        val keychain = InMemoryKeychainPort()
        var adds = 0
        var updates = 0
        keychain.script = { operation: String, _: Map<String, String> ->
            when (operation) {
                "add" -> if (++adds == 1) keychain.seed(keychain.item("k", "theirs"))
                "update" -> if (++updates == 2) keychain.removeBehindTheStore("k")
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        val result: StorageResult<Unit> = store.put("k", "ours")

        assertEquals(
            StorageResult.Success(Unit),
            result,
            "a put that raced one writer and one remover must not fail; calls: ${keychain.operations()}",
        )
        keychain.script = null
        assertEquals(StorageResult.Success("ours"), store.get("k"), "the value is readable once put returned")
        assertEquals(1, keychain.items.size)
    }

    @Test
    fun `a put against an endless add and remove adversary ends after a bounded number of Keychain calls`() = runTest {
        // Livelock bound: every add of ours meets a fresh item of another writer and every update of ours meets a
        // remover. Whatever retry the store does, it must give up in a bounded number of calls and say so: a
        // Failure for PUT and this key, never a Success for a value that is not there.
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> ->
            when (operation) {
                "add" -> if (keychain.items.isEmpty()) keychain.seed(keychain.item("k", "theirs"))
                "update" -> keychain.removeBehindTheStore("k")
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        val result: StorageResult<Unit> = store.put("k", "ours")

        val calls: List<String> = keychain.operations().filter { it == "add" || it == "update" }
        assertTrue(calls.size <= MAX_WRITE_CALLS, "the retry must be bounded: ${calls.size} calls")
        when (result) {
            is StorageResult.Success -> fail("a put whose value never landed reported Success; calls: $calls")
            is StorageResult.Failure -> {
                val error: StorageError = result.error
                assertTrue(
                    error is StorageError.OperationFailed && error.operation == StorageOperation.PUT && error.key == "k",
                    "the give-up names PUT and the key: $error",
                )
            }
        }
    }

    @Test
    fun `a put cancelled while its add is blocked leaves one whole item or none and makes no call afterwards`() =
        runTest(timeout = 120.seconds) {
            val keychain = InMemoryKeychainPort()
            val addReached = CompletableDeferred<Unit>()
            val releaseAdd = CompletableDeferred<Unit>()
            keychain.script = { operation: String, _: Map<String, String> ->
                if (operation == "add" && !addReached.isCompleted) {
                    addReached.complete(Unit)
                    runBlocking { releaseAdd.await() }
                }
                null
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)

            val writer: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.put("k", "ours") }
            addReached.await()
            assertTrue(writer.isActive, "control: the put is inside its add")
            writer.cancel()
            releaseAdd.complete(Unit)
            writer.join()

            assertTrue(writer.isCancelled)
            val callsAtEnd: List<String> = keychain.operations()
            val items: List<InMemoryKeychainPort.Item> = keychain.items
            assertTrue(items.size <= 1, "at most one item: ${items.size}")
            items.singleOrNull()?.let { item ->
                assertEquals("k", item.account)
                assertEquals("afterFirstUnlockThisDeviceOnly", item.accessible, "a whole item carries its accessibility")
            }
            keychain.script = null
            val read: StorageResult<String?> = store.get("k")
            assertTrue(
                read == StorageResult.Success(null) || read == StorageResult.Success("ours"),
                "a cancelled put leaves the old state or the whole new value, never a torn one: $read",
            )
            assertEquals(callsAtEnd, keychain.operations().dropLast(1), "no Keychain call of the cancelled put after it ended")
        }

    @Test
    fun `a Keychain call that throws does not wedge the store`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "add") throw IllegalStateException("seam failure") else null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        val thrown: Result<StorageResult<Unit>> = runCatching { store.put("k", "v") }
        assertTrue(thrown.getOrNull() !is StorageResult.Success, "a write whose add threw cannot be a Success: $thrown")

        keychain.script = null
        assertEquals(StorageResult.Success(Unit), store.put("k", "v2"), "the store works after a throwing call")
        assertEquals(StorageResult.Success("v2"), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.remove("k"))
        assertEquals(StorageResult.Success(null), store.get("k"))
    }

    private companion object {
        /** A generous bound on the update and add calls of one put; any fixed bound is fine, no bound is not. */
        const val MAX_WRITE_CALLS: Int = 64
    }
}
