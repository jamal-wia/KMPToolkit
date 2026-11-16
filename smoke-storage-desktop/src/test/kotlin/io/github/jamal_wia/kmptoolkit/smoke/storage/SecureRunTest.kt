package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * The wiring a packaged secure run executes (`runSecureRoundTrip`, Main.kt hands it a real store and the real removal): the round trip,
 * the cleanup on every way out, the control that the key item is found at the derived identity, and the report of a failure. The store is
 * a fake that makes its key item in a fake keychain on the first `put`, exactly as the real one does, so "the item existed before the
 * cleanup" is a control the tests can assert, and a leftover is visible.
 * (Breaker round 2, finding M1: the failure path of a secure run was unguarded and untested.)
 */
class SecureRunTest {

    private val item: MacKeyItem = MacKeyItem("smoke.run.kmptoolkit.securestorage", "dir-0123456789abcdef0123456789abcdef")
    private val keychain: FakeKeychain = FakeKeychain()
    private val removal: KeyItemRemoval = MacKeyItemRemoval(keychain, item)

    private fun store(): FakeSecureStore = FakeSecureStore(keychain, itemOfTheStore = item)

    @Test
    fun `a passing run puts the key item, finds it at the control, and leaves nothing`() = runTest {
        val store: FakeSecureStore = store()
        runSecureRoundTrip(store, openSecond = { store }, removal = removal)
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "the control read comes first, then the delete, then the proof of absence")
        assertTrue(keychain.items.isEmpty(), "no key item is left")
        assertTrue(store.cleared, "the store's data is cleared")
        assertTrue(store.itemWasMade, "control of the fake: the first put did make the item, so the removal had something to remove")
    }

    @Test
    fun `a failing round trip after the put still removes the item, runs the control, and rethrows the original failure`() = runTest {
        val store: FakeSecureStore = store().apply { failGet = true }
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
        assertTrue("secure get failed" in failure.message.orEmpty(), failure.message)
        assertTrue(store.itemWasMade, "control of the fake: the item existed before the cleanup")
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "the cleanup read the item first: the control is on once the first put succeeded")
        assertTrue(keychain.items.isEmpty(), "the item of a failed run is removed too")
        assertTrue(store.cleared)
        assertTrue(failure.suppressed.isEmpty(), "nothing else failed")
    }

    @Test
    fun `after a successful put a wrong identity fails loudly on a failed run too, and the leftover is reported`() = runTest {
        // The store makes its item at an identity other than the one the smoke derived: the copy of the library's derivation drifted.
        val otherIdentity: MacKeyItem = MacKeyItem("drifted.kmptoolkit.securestorage", "dir-ffffffffffffffffffffffffffffffff")
        val store: FakeSecureStore = FakeSecureStore(keychain, itemOfTheStore = otherIdentity).apply { failGet = true }
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
        assertTrue("secure get failed" in failure.message.orEmpty(), "the verdict names the first failure: ${failure.message}")
        val details: List<String> = failureDetails(failure)
        assertEquals(1, details.size, details.toString())
        assertTrue("control failed" in details.single() && item.service in details.single(), "the control failure is shown: $details")
        assertEquals(1, keychain.items.size, "the item the library made is still there, and now it is REPORTED, not silent")
        assertFalse("delete" in keychain.calls, "nothing is deleted at an identity the control did not find")
    }

    @Test
    fun `a failure before the first put has made no key, so an absent item is fine`() = runTest {
        val store: FakeSecureStore = store().apply { failPut = true }
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
        assertTrue("secure put failed" in failure.message.orEmpty(), failure.message)
        assertFalse(store.itemWasMade, "control of the fake: no item was ever made")
        assertTrue(failure.suppressed.isEmpty(), "an absent item before the key was made is not a failure of the cleanup")
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "the removal is still asked, it only does not insist the item is there")
    }

    @Test
    fun `a failing removal on a failed run is reported next to the original failure`() = runTest {
        val store: FakeSecureStore = store().apply { failGet = true }
        keychain.deleteStatus = -25293
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
        assertTrue("secure get failed" in failure.message.orEmpty(), failure.message)
        val details: List<String> = failureDetails(failure)
        assertTrue(details.any { "-25293" in it }, "the removal's failure is printed, never swallowed: $details")
        assertEquals(1, keychain.items.size, "the item is still there (the delete failed) and the report says so")
    }

    @Test
    fun `a failing data clear does not stop the key item removal, and both are reported`() = runTest {
        val store: FakeSecureStore = store().apply { failClear = true }
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
        assertTrue("secure clear failed" in failure.message.orEmpty(), failure.message)
        assertTrue(keychain.items.isEmpty(), "the key item is removed although the data clear failed")
    }

    @Test
    fun `a cancelled round trip removes the item too`() = runTest {
        val store: FakeSecureStore = store().apply { getHangs = true }
        assertFailsWith<Throwable> { withTimeout(1_000L) { runSecureRoundTrip(store, openSecond = { store }, removal = removal) } }
        assertTrue(store.itemWasMade, "control of the fake: the item existed before the cancellation")
        assertTrue(keychain.items.isEmpty(), "cleanup runs in NonCancellable: the item is gone after a cancelled run")
        assertTrue(store.cleared)
    }

    // --- the report of a failure ---

    @Test
    fun `the verdict names only the first failure and the suppressed ones are listed apart, nested ones indented`() {
        val first: SmokeFailure = SmokeFailure("first")
        val second: SmokeFailure = SmokeFailure("second")
        val third: SmokeFailure = SmokeFailure("third")
        first.addSuppressed(second)
        second.addSuppressed(third)
        assertTrue(failureVerdict(first).startsWith("SMOKE-RESULT: FAIL "), failureVerdict(first))
        assertTrue(failureVerdict(first).endsWith("first") && "second" !in failureVerdict(first))
        val lines: List<String> = failureDetails(first)
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines[0].startsWith("ALSO FAILED") && lines[0].endsWith("second"), lines[0])
        assertTrue(lines[1].startsWith("  ALSO FAILED") && lines[1].endsWith("third"), lines[1])
    }

    @Test
    fun `a failure with nothing suppressed has no detail lines, and a cycle of suppressed failures ends`() {
        assertEquals(emptyList(), failureDetails(SmokeFailure("alone")))
        val a: SmokeFailure = SmokeFailure("a")
        val b: SmokeFailure = SmokeFailure("b")
        a.addSuppressed(b)
        b.addSuppressed(a)
        assertEquals(2, failureDetails(a).size)
    }

    /** A secure store whose first `put` makes its key item in [keychain], like the real one; every failure mode is a field. */
    private class FakeSecureStore(private val keychain: FakeKeychain, private val itemOfTheStore: MacKeyItem) : SecureKeyValueStorage {
        private val data: MutableMap<String, String> = mutableMapOf()
        var failPut: Boolean = false
        var failGet: Boolean = false
        var failClear: Boolean = false
        var getHangs: Boolean = false
        var cleared: Boolean = false
        var itemWasMade: Boolean = false

        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_USER_SCOPED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun get(key: String): StorageResult<String?> {
            if (getHangs) delay(Long.MAX_VALUE)
            if (failGet) return StorageResult.Failure(StorageError.OperationFailed(StorageOperation.GET, key = key))
            return StorageResult.Success(data[key])
        }

        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            if (failPut) return StorageResult.Failure(StorageError.OperationFailed(StorageOperation.PUT, key = key))
            if (!itemWasMade) {
                keychain.items[itemOfTheStore.service to itemOfTheStore.account] = ByteArray(32) { 1 }
                itemWasMade = true
            }
            data[key] = value
            return StorageResult.Success(Unit)
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            data.remove(key)
            return StorageResult.Success(Unit)
        }

        override suspend fun clear(): StorageResult<Unit> {
            if (failClear) return StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR))
            cleared = true
            data.clear()
            return StorageResult.Success(Unit)
        }
    }
}
