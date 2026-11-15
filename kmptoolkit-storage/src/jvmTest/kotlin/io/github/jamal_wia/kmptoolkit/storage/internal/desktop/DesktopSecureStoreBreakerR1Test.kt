package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * Breaker round 1 of phase B of group `sto-desktop` (plan 4.4, 4.5, 4.8, 4.11; review/sto-desktop-B-breaker-1). Every test
 * runs over the fake binding (no real OS key store), real files in a directory the test creates and deletes, and real
 * threads bounded by hang timeouts only.
 *
 * Attacked: the store identity (two directories or two names that are different files must never share or destroy each
 * other's key), the LinkageError backstop around every binding call, the crash state of plan 4.4 "Create" for a store that
 * may not show a dialog, the provider-once rule of `changeKey`, and two instances racing `changeKey`.
 */
class DesktopSecureStoreBreakerR1Test {

    private fun <T> withRig(os: DesktopOs = DesktopOs.MACOS, block: suspend SecureTestRig.() -> T): T =
        SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    // ---- identity: B1, M1 ----

    /**
     * Plan 4.4 rules: "Never delete an item outside the own identity (name plus directory fingerprint)"; docs 05: "two
     * directories with one store name are two stores with two keys". `t_ı` (dotless i) and `t_i` are two directories on
     * APFS (and on every case-sensitive Linux file system, as are `App` and `app`), but the identity folds them into one
     * fingerprint: the second store's first `put` deletes the first store's key item and replaces it with its own, so the
     * first store's values can never be decrypted again (the key is gone from the OS, not quarantined).
     * Red on: identity derived from the case-folded directory (`StoreIdentity.directoryHex` over `jvmStorageLockKey`).
     */
    @Test
    fun `two different directories whose names fold alike never destroy each other's key`() = withRig {
        val first = File(directory, "t_ı")
        val second = File(directory, "t_i")
        assertTrue(first.mkdir() && second.mkdir(), "both directories were created")
        assumeTrue("the volume folds the two names into one directory", !Files.isSameFile(first.toPath(), second.toPath()))

        assertEquals(StorageResult.Success(Unit), store(dir = first).put("a", "1"))
        assertEquals(StorageResult.Success(Unit), store(dir = second).put("b", "2"))

        // A new process (empty key cache) opens the first store again.
        val reopened: JvmSecureKeyValueStorage = store(dir = first, cache = OsKeyCache())
        assertEquals(StorageResult.Success("1"), reopened.get("a"), "the first store's value survives a second store in another directory")
        assertEquals(StorageResult.Success("2"), store(dir = second, cache = OsKeyCache()).get("b"))
        assertEquals(2, fake.macItems.size, "two directories are two identities with two key items: ${fake.macItems.keys}")
    }

    /**
     * Two store names that differ only in case name ONE file on a case-insensitive volume (macOS default, Windows), and one
     * lock (the lock key folds), but two identities (the service is not folded). Each store then reads the other's header as
     * a foreign identity, i.e. "key lost", and its next `put` quarantines the other store's file: a value written with a
     * successful `put` silently disappears. Red on: identity service not derived the way the file/lock is.
     */
    @Test
    fun `two store names that land on one file never quarantine each other's values`() = withRig {
        val upper: JvmSecureKeyValueStorage = store(name = "Case")
        assertEquals(StorageResult.Success(Unit), upper.put("a", "1"))
        assumeTrue("the volume is case-sensitive: the two names are two files", dataFile(name = "case").exists())

        val lower: JvmSecureKeyValueStorage = store(name = "case")
        val lowerPut: StorageResult<Unit> = lower.put("b", "2")

        assertEquals(StorageResult.Success("1"), upper.get("a"), "lower.put answered $lowerPut; files now: ${fileNames()}")
        assertTrue(fileNames().none { ".undecryptable-" in it }, "nothing was quarantined: ${fileNames()}")
    }

    // ---- LinkageError backstop: m1 ----

    /**
     * Plan 4.2 step 5: "any LinkageError around a binding call ... is also Unsupported(NATIVE_ACCESS_UNAVAILABLE), never a
     * thrown exception"; the seam KDoc counts the three OS accessors as calls that may throw it. The store and the probe call
     * `binding.macOs()` outside the native executor's backstop, so the Error escapes the suspend function.
     */
    @Test
    fun `a LinkageError from a binding's OS accessor is Unsupported, never thrown, from the store and from the probe`() {
        val broken = object : NativeKeyStoreBinding {
            override fun macOs(): MacKeychainCalls = throw NoSuchMethodError("MacKeychainCalls.copyGenericPassword")
            override fun windows(): DpapiCalls = throw NoSuchMethodError("DpapiCalls.protect")
            override fun linux(): SecretServiceCalls = throw NoSuchMethodError("SecretServiceCalls.lookup")
        }
        val source = FixedBindingSource(BindingSelection.Selected(broken, DesktopOs.MACOS, BindingKind.FAKE))
        withRig {
            val store: JvmSecureKeyValueStorage = store(source = source)
            val put: Result<StorageResult<Unit>> = runCatching { store.put("k", "v") }
            val putError: StorageError? = (put.getOrNull() as? StorageResult.Failure)?.error
            assertTrue(
                putError is StorageError.Unsupported && putError.reason == UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE,
                "put answered $put",
            )
            assertTrue(fileNames().isEmpty(), "nothing was written: ${fileNames()}")

            val probe = DesktopKeyStoreProbe(source, executor, SecureStoreEnvironment(getenv = { null }, exists = { false }))
            val probed: Result<StorageResult<DesktopKeyStore>> = runCatching { probe.probe(DesktopSecureStorageOptions()) }
            val error: StorageError? = (probed.getOrNull() as? StorageResult.Failure)?.error
            assertTrue(
                error is StorageError.Unsupported && error.reason == UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE,
                "probe answered $probed",
            )
        }
    }

    // ---- crash state of plan 4.4 "Create" without UI: M2 ----

    /**
     * Plan 4.4 "Create": "A crash between steps 2 and 3 leaves an empty sealed file with no item. Reads answer Success(null),
     * and the next put or ensureKey() creates the key, losing nothing" (repeated in 05). On macOS with
     * `allowUserInteraction = false` the conservative -25300 rule makes every call of such a store `Unavailable`, for good:
     * nothing can be lost in a zero-entry file, yet a CLI tool or a service can never use the store again until a human
     * deletes the file.
     */
    @Test
    fun `a zero-entry store without its item is usable again without UI on macOS`() = withRig {
        val interactive: JvmSecureKeyValueStorage = store()
        assertEquals(StorageResult.Success(Unit), interactive.put("k", "v"))
        assertEquals(StorageResult.Success(Unit), interactive.remove("k"))
        // The crash state: the empty sealed file is there, the item never made it into the keychain.
        fake.macItems.clear()

        val quiet: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cache = OsKeyCache())
        assertEquals(StorageResult.Success(null), quiet.get("k"), "a read of the crash state answers Success(null)")
        assertEquals(StorageResult.Success(Unit), quiet.put("k", "w"), "the next put creates the key")
        assertEquals(StorageResult.Success("w"), quiet.get("k"))
    }

    // ---- changeKey asks one provider twice: m2 ----

    /**
     * Plan 3.4 / 4.4 rules: "The key provider (app-key store) is called at most once per operation." A re-key with the SAME
     * key object (a fresh salt and data key under the same passphrase, e.g. after raising the iteration count) is one
     * operation with one provider, yet `changeKey` keeps two scopes (current and new) and asks the provider twice: a
     * passphrase dialog shown twice for one call.
     */
    @Test
    fun `changeKey with the instance's own key object asks its provider once`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-breaker-samekey").toFile()
        try {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val key = AppEncryptionKey.Raw {
                calls.incrementAndGet()
                ByteArray(32) { it.toByte() }
            }
            runBlocking {
                withTimeout(HANG_BOUND) {
                    assertEquals(StorageResult.Success(Unit), JvmAppKeyEncryptedKeyValueStorage("same", directory, key, Dispatchers.IO).put("k", "v"))
                    val store = JvmAppKeyEncryptedKeyValueStorage("same", directory, key, Dispatchers.IO)
                    calls.set(0)
                    assertEquals(StorageResult.Success(Unit), store.changeKey(key))
                    assertEquals(1, calls.get(), "provider calls for one changeKey")
                    assertEquals(StorageResult.Success("v"), store.get("k"))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- changeKey race: GREEN proof ----

    /**
     * Two instances over one file, both holding the current key, run `changeKey` to two different new keys at the same time
     * (latch start, real threads). Exactly one wins; the loser is `AccessDenied` (its current key no longer opens the file)
     * and changed nothing; the file opens with the winner's new key and every value survives; the old key and the loser's
     * new key open nothing.
     */
    @Test
    fun `two instances racing changeKey leave one complete file under exactly one new key`() {
        val seed = 20261115
        val random = Random(seed)
        repeat(RACE_ROUNDS) { round: Int ->
            val directory: File = Files.createTempDirectory("kmptoolkit-breaker-rekey").toFile()
            try {
                val old: AppEncryptionKey.Raw = rawKey(random)
                val newA: AppEncryptionKey.Raw = rawKey(random)
                val newB: AppEncryptionKey.Raw = rawKey(random)
                val values: Map<String, String> = (0 until 6).associate { "k$it" to "v$round-$it-${random.nextInt()}" }
                runBlocking {
                    withTimeout(HANG_BOUND) {
                        val writer = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, old, Dispatchers.IO)
                        for ((k: String, v: String) in values) assertEquals(StorageResult.Success(Unit), writer.put(k, v), "seed=$seed round=$round")
                        val a = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, old, Dispatchers.IO)
                        val b = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, old, Dispatchers.IO)
                        // Both verify the current key first, so the race starts with two unlocked instances.
                        assertEquals(StorageResult.Success(values["k0"]), a.get("k0"), "seed=$seed round=$round")
                        assertEquals(StorageResult.Success(values["k0"]), b.get("k0"), "seed=$seed round=$round")
                        val start = CountDownLatch(1)
                        val results: List<StorageResult<Unit>> = coroutineScope {
                            listOf(a to newA, b to newB).map { (store: JvmAppKeyEncryptedKeyValueStorage, next: AppEncryptionKey) ->
                                async(Dispatchers.Default) {
                                    check(start.await(LATCH_SECONDS, TimeUnit.SECONDS)) { "seed=$seed start latch" }
                                    store.changeKey(next)
                                }
                            }.also { start.countDown() }.awaitAll()
                        }
                        val winners: List<Int> = results.indices.filter { results[it] is StorageResult.Success }
                        assertEquals(1, winners.size, "seed=$seed round=$round exactly one changeKey wins: $results")
                        val loser: StorageResult<Unit> = results[1 - winners.single()]
                        assertIs<StorageError.AccessDenied>(failure(loser), "seed=$seed round=$round")
                        val winnerKey: AppEncryptionKey = if (winners.single() == 0) newA else newB
                        val loserKey: AppEncryptionKey = if (winners.single() == 0) newB else newA
                        val reader = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, winnerKey, Dispatchers.IO)
                        for ((k: String, v: String) in values) assertEquals(StorageResult.Success(v), reader.get(k), "seed=$seed round=$round key=$k")
                        assertIs<StorageError.AccessDenied>(failure(JvmAppKeyEncryptedKeyValueStorage("rekey", directory, old, Dispatchers.IO).get("k0")), "seed=$seed")
                        assertIs<StorageError.AccessDenied>(failure(JvmAppKeyEncryptedKeyValueStorage("rekey", directory, loserKey, Dispatchers.IO).get("k0")), "seed=$seed")
                        assertTrue(directory.list().orEmpty().none { ".undecryptable-" in it }, "seed=$seed ${directory.list()?.toList()}")
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun rawKey(random: Random): AppEncryptionKey.Raw {
        val bytes: ByteArray = random.nextBytes(32)
        return AppEncryptionKey.Raw { bytes.copyOf() }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
        const val LATCH_SECONDS: Long = 30
        const val RACE_ROUNDS: Int = 12
    }
}
