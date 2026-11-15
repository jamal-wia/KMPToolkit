package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `userPresence` (plan 4.10, 6.6): what the store asks of the Keychain and of the authentication session for a
 * store that gates its items behind the system sheet, and what happens to the session and to the waiting caller when
 * the sheet is cancelled, fails, or is abandoned by a cancelled coroutine.
 *
 * The sheet itself, and the status the real Keychain answers for a changed biometry set, are device behaviour and
 * belong to the `storage-ios-harness` module ("not observed on a device" until the owner runs it).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosUserPresenceTest {

    private val presence: IosSecureStorageOptions = IosSecureStorageOptions(
        userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Unlock your tokens"),
    )

    private fun StorageResult<*>.error(): StorageError = (this as StorageResult.Failure).error

    // --- what is asked --------------------------------------------------------------------------------------

    @Test
    fun `a get opens one session with the app's prompt and passes it to the Keychain and releases it`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "secret"))
        val sessions = CountingAuthenticationSessions()
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

        assertEquals(StorageResult.Success("secret"), store.get("k"))

        assertEquals(listOf("Unlock your tokens"), sessions.prompts)
        assertEquals(1, sessions.opened)
        assertEquals(1, sessions.invalidated, "the context must be released when the call ends")
        assertEquals("object", keychain.calls.single().query["context"])
    }

    @Test
    fun `every get opens its own session because the sheet shows on every get`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("a", "1"))
        keychain.seed(keychain.item("b", "2"))
        val sessions = CountingAuthenticationSessions()
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

        assertEquals(StorageResult.Success("1"), store.get("a"))
        assertEquals(StorageResult.Success("2"), store.get("b"))
        assertEquals(StorageResult.Success("1"), store.get("a"))

        assertEquals(3, sessions.opened)
        assertEquals(3, sessions.invalidated)
    }

    @Test
    fun `a store without user presence never opens a session`() = runTest {
        val keychain = InMemoryKeychainPort()
        val sessions = CountingAuthenticationSessions()
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(), sessions)

        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
        assertEquals(StorageResult.Success("v"), store.get("k"))

        assertEquals(0, sessions.opened)
    }

    @Test
    fun `a put protects the item with an access control and an overwrite updates it with the same`() = runTest {
        val keychain = InMemoryKeychainPort()
        val sessions = CountingAuthenticationSessions()
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

        assertEquals(StorageResult.Success(Unit), store.put("k", "v1"))
        assertEquals(StorageResult.Success(Unit), store.put("k", "v2"))

        val add: InMemoryKeychainPort.Call = keychain.calls.single { it.operation == "add" }
        assertEquals("object", add.query["accessControl"])
        assertFalse("accessible" in add.query)
        val overwrite: InMemoryKeychainPort.Call = keychain.calls.last()
        assertEquals("update", overwrite.operation)
        assertEquals("object", overwrite.attributes!!["accessControl"])
        assertFalse("accessible" in overwrite.attributes)
        assertTrue(keychain.items.single().accessControl)
        assertEquals(sessions.opened, sessions.invalidated)
    }

    @Test
    fun `ensureKey and remove and clear never open a session or prompt`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k"))
        val sessions = CountingAuthenticationSessions()
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

        assertEquals(StorageResult.Success(Unit), store.ensureKey())
        assertEquals(StorageResult.Success(Unit), store.remove("k"))
        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(0, sessions.opened)
        assertTrue(keychain.calls.none { "context" in it.query })
        assertEquals("fail", keychain.calls.first { it.operation == "matches" }.query["authUI"])
    }

    // --- the table: cancel and failed authentication --------------------------------------------------------

    @Test
    fun `a cancelled sheet is AccessDenied -128 and a failed authentication is AccessDenied -25293`() = runTest {
        for ((status: Int, expected: StorageError) in listOf(
            ERR_SEC_USER_CANCELED to StorageError.AccessDenied(-128),
            ERR_SEC_AUTH_FAILED to StorageError.AccessDenied(-25293),
        )) {
            val keychain = InMemoryKeychainPort()
            keychain.script = { operation: String, _: Map<String, String> ->
                if (operation == "readData" || operation == "update") status else null
            }
            val sessions = CountingAuthenticationSessions()
            val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions)

            assertEquals(expected, store.get("k").error(), "get with $status")
            assertEquals(expected, store.put("k", "v").error(), "put with $status")
            assertEquals(sessions.opened, sessions.invalidated, "sessions leaked with $status")
            assertTrue(keychain.items.isEmpty(), "a refused write must change nothing")
        }
    }

    @Test
    fun `a changed biometry set is reported by the status the Keychain gives and never invented here`() = runTest {
        // errSecDecode is the one status mapped to Undecryptable(key); the status iOS really answers after a
        // re-enrollment is recorded by the harness case, not assumed here.
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "readData") ERR_SEC_DECODE else null }

        assertEquals(StorageError.Undecryptable("k"), storeOver(keychain, presence).get("k").error())
    }

    // --- cancellation mid-prompt ----------------------------------------------------------------------------

    @Test
    fun `cancelling a get that waits on the sheet dismisses the sheet and returns and releases the session`() =
        runTest(timeout = 120.seconds) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("k", "secret"))
            val sessions = CountingAuthenticationSessions()
            val sheetDismissed = CompletableDeferred<Unit>()
            val sheetShown = CompletableDeferred<Unit>()
            // Dismissing the sheet (invalidating the context) is what makes the blocked Keychain call return.
            sessions.onInvalidate = { sheetDismissed.complete(Unit) }
            keychain.script = { operation: String, _: Map<String, String> ->
                if (operation == "readData") {
                    sheetShown.complete(Unit)
                    runBlocking { sheetDismissed.await() }
                    ERR_SEC_USER_CANCELED
                } else {
                    null
                }
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)

            val waiting: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.get("k") }
            sheetShown.await()
            assertEquals(1, sessions.opened, "control: the session exists while the sheet is up")
            assertEquals(0, sessions.invalidated, "control: nothing dismissed the sheet yet")
            assertTrue(waiting.isActive)

            waiting.cancel()
            waiting.join()

            assertTrue(waiting.isCancelled)
            assertEquals(1, sessions.invalidated, "cancelling must dismiss the sheet and release the context")
            assertEquals(1, sessions.opened)
        }

    @Test
    fun `cancelling a put that waits on the sheet writes nothing`() = runTest(timeout = 120.seconds) {
        val keychain = InMemoryKeychainPort()
        val sessions = CountingAuthenticationSessions()
        val sheetDismissed = CompletableDeferred<Unit>()
        val sheetShown = CompletableDeferred<Unit>()
        sessions.onInvalidate = { sheetDismissed.complete(Unit) }
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "update") {
                sheetShown.complete(Unit)
                runBlocking { sheetDismissed.await() }
                ERR_SEC_USER_CANCELED
            } else {
                null
            }
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)

        val waiting: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.put("k", "v") }
        sheetShown.await()
        waiting.cancel()
        waiting.join()

        assertTrue(keychain.items.isEmpty(), "a put abandoned at the sheet must leave no half-written item")
        assertEquals(sessions.opened, sessions.invalidated)
        assertEquals(listOf("update"), keychain.operations(), "no add may follow a dismissed update")
    }

    @Test
    fun `a prompt that is up holds no lock and so other operations of the store proceed meanwhile`() =
        runTest(timeout = 120.seconds) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("k", "secret"))
            keychain.seed(keychain.item("other", "x"))
            val sessions = CountingAuthenticationSessions()
            val sheetDismissed = CompletableDeferred<Unit>()
            val sheetShown = CompletableDeferred<Unit>()
            sessions.onInvalidate = { sheetDismissed.complete(Unit) }
            keychain.script = { operation: String, q: Map<String, String> ->
                if (operation == "readData" && q["account"] == "k") {
                    sheetShown.complete(Unit)
                    runBlocking { sheetDismissed.await() }
                    ERR_SEC_USER_CANCELED
                } else {
                    null
                }
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)

            val waiting: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.get("k") }
            sheetShown.await()

            // The sheet is still up; none of these may wait for it.
            assertEquals(StorageResult.Success(Unit), store.ensureKey())
            assertEquals(StorageResult.Success(Unit), store.remove("other"))
            assertFalse(sheetDismissed.isCompleted, "control: the sheet was still up for those calls")

            waiting.cancel()
            waiting.join()
            assertEquals(sessions.opened, sessions.invalidated)
        }

    @Test
    fun `a new store works after the old one's prompt was abandoned`() = runTest(timeout = 120.seconds) {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "secret"))
        val sessions = CountingAuthenticationSessions()
        val sheetDismissed = CompletableDeferred<Unit>()
        val sheetShown = CompletableDeferred<Unit>()
        sessions.onInvalidate = { sheetDismissed.complete(Unit) }
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "readData" && !sheetShown.isCompleted) {
                sheetShown.complete(Unit)
                runBlocking { sheetDismissed.await() }
                ERR_SEC_USER_CANCELED
            } else {
                null
            }
        }
        val first: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)
        val waiting: Job = launch(start = CoroutineStart.UNDISPATCHED) { first.get("k") }
        sheetShown.await()
        waiting.cancel()
        waiting.join()

        val second: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.Default)

        assertEquals(StorageResult.Success("secret"), second.get("k"))
        assertEquals(2, sessions.opened)
        assertEquals(2, sessions.invalidated)
    }
}
