package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Group sto-desktop, round 4 (phase B breaker round 1 and the reading findings R1-R3), over the fake binding and real files
 * in a directory the test creates and deletes. The breaker class `DesktopSecureStoreBreakerR1Test` is the proof for B1,
 * M1, M2, m1, m2; these tests pin the rest of each decision: the identity is the stored spelling of the real path (no
 * folding, no fallback, never memoised), a zero-entry crash state is repaired without UI while a file with entries keeps
 * the conservative rule, the accessors of the seam are guarded, the store file never passes the read limit, and a failed
 * rollback is never silent.
 */
class DesktopSecureStoreRound4Test {

    private fun <T> withRig(os: DesktopOs = DesktopOs.MACOS, block: suspend SecureTestRig.() -> T): T =
        SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    private fun SecureTestRig.macKey(): Pair<String, String> {
        val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, directory)
        return identity.service to identity.macAccount
    }

    private fun quiet(): DesktopSecureStorageOptions = DesktopSecureStorageOptions(allowUserInteraction = false)

    /** A store with entries is written by an interactive store; its key item is then gone (the crash or a deleted keychain item). */
    private suspend fun SecureTestRig.crashState(withEntries: Boolean): ByteArray {
        val interactive: JvmSecureKeyValueStorage = store()
        assertEquals(StorageResult.Success(Unit), interactive.put("k", "v"))
        if (!withEntries) assertEquals(StorageResult.Success(Unit), interactive.remove("k"))
        fake.macItems.clear()
        return dataFile().readBytes()
    }

    // ---- identity (B1, M1, R1) ----

    @Test
    fun `identities of paths that only differ in case or in dotless i are different, no folding`() {
        fun id(directory: String, name: String = "n.kmptoolkit.securestorage.bin"): StoreIdentity =
            StoreIdentity.of(RealStorePath(directory, name), normalizeUnicode = false)

        assertNotEquals(id("/x/App").cacheKey, id("/x/app").cacheKey, "a case-sensitive volume has two directories")
        assertNotEquals(id("/x/t_ı").cacheKey, id("/x/t_i").cacheKey)
        assertNotEquals(id("/x", "Case.kmptoolkit.securestorage.bin").cacheKey, id("/x", "case.kmptoolkit.securestorage.bin").cacheKey)
        assertEquals(id("/x/App").cacheKey, id("/x/App").cacheKey, "stable")
        assertEquals("Case.kmptoolkit.securestorage", id("/x", "Case.kmptoolkit.securestorage.bin").service, "the service is the stored name")
    }

    @Test
    fun `on macOS Unicode normalization forms of one name are one identity, elsewhere the stored form is kept`() {
        val nfc = "café"
        val nfd = "café"
        fun id(name: String, normalize: Boolean): StoreIdentity =
            StoreIdentity.of(RealStorePath("/x/$nfc", "$name.bin"), normalizeUnicode = normalize)

        assertEquals(id(nfc, true).cacheKey, id(nfd, true).cacheKey)
        assertEquals(id(nfc, true).directoryHex, StoreIdentity.of(RealStorePath("/x/$nfd", "$nfc.bin"), normalizeUnicode = true).directoryHex)
        assertNotEquals(id(nfc, false).cacheKey, id(nfd, false).cacheKey, "two files on a volume that keeps the forms apart")
    }

    @Test
    fun `two spellings of one store name are one file, one item and one set of values (macOS)`() = withRig {
        val upper: JvmSecureKeyValueStorage = store(name = "Case")
        assertEquals(StorageResult.Success(Unit), upper.put("a", "1"))
        assumeTrue("the volume is case-sensitive: two names are two files", dataFile(name = "case").exists())
        val storedName: String = fileNames().single { it.endsWith(".bin") }

        val lower: JvmSecureKeyValueStorage = store(name = "case", cache = OsKeyCache())
        assertEquals(StorageResult.Success("1"), lower.get("a"), "the other spelling reads the value of the first")
        assertEquals(StorageResult.Success(Unit), lower.put("b", "2"))
        assertEquals(StorageResult.Success("2"), store(name = "Case", cache = OsKeyCache()).get("b"))

        assertEquals(listOf(storedName), fileNames().filter { it.endsWith(".bin") }, "the stored spelling never changed")
        assertEquals(1, fake.macItems.size, "one item: ${fake.macItems.keys}")
        assertTrue(quarantined().isEmpty(), "nothing was quarantined: ${fileNames()}")
    }

    @Test
    fun `two spellings of one store name share the DPAPI entropy on Windows`() = withRig(DesktopOs.WINDOWS) {
        val upper: JvmSecureKeyValueStorage = store(name = "Case")
        assertEquals(StorageResult.Success(Unit), upper.put("a", "1"))
        assumeTrue("the volume is case-sensitive: two names are two files", dataFile(name = "case").exists())

        assertEquals(StorageResult.Success("1"), store(name = "case", cache = OsKeyCache()).get("a"))
        assertEquals(StorageResult.Success(Unit), store(name = "case", cache = OsKeyCache()).put("b", "2"))
        assertEquals(StorageResult.Success("2"), upper.get("b"))
    }

    @Test
    fun `a put of the other spelling over a lost key keeps the stored spelling and leaves one item for both`() = withRig {
        val upper: JvmSecureKeyValueStorage = store(name = "Case")
        assertEquals(StorageResult.Success(Unit), upper.put("a", "1"))
        assumeTrue("the volume is case-sensitive: two names are two files", dataFile(name = "case").exists())
        val storedName: String = fileNames().single { it.endsWith(".bin") }
        fake.macItems.clear()

        val lower: JvmSecureKeyValueStorage = store(name = "case", cache = OsKeyCache())
        assertEquals(StorageResult.Success(Unit), lower.put("c", "3"), "key lost with entries: quarantine, then a new key")

        assertEquals(listOf(storedName), fileNames().filter { it.endsWith(".bin") }, "the new file has the spelling the old one had")
        assertEquals(1, quarantined().size, fileNames().toString())
        assertEquals(1, fake.macItems.size)
        assertEquals(StorageResult.Success("3"), store(name = "Case", cache = OsKeyCache()).get("c"))
        assertEquals(StorageResult.Success("3"), store(name = "case", cache = OsKeyCache()).get("c"))
    }

    private class FlakyResolver(private val failOn: Set<Int>, private val error: () -> Throwable) : StorePathResolver {
        val calls: AtomicInteger = AtomicInteger()

        override fun resolve(file: File): RealStorePath {
            if (calls.incrementAndGet() in failOn) throw error()
            return StorePathResolver.real.resolve(file)
        }
    }

    @Test
    fun `a file system that cannot be asked fails that call typed, touches nothing, and the next call works (never memoised)`() = withRig {
        val resolver = FlakyResolver(setOf(1), { IOException("transient (test)") })
        val store: JvmSecureKeyValueStorage = store(resolver = resolver)

        val failed: StorageError = failure(store.put("k", "v"))

        assertEquals(StorageOperation.PUT, assertIs<StorageError.OperationFailed>(failed).operation)
        assertEquals("k", failed.key)
        assertIs<IOException>(failed.cause)
        assertEquals(emptyList(), fileNames(), "nothing was created: no store file, no lock file, no quarantine")
        assertEquals(0, fake.calls.size, "no OS call was made")

        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
        assertEquals(StorageResult.Success("v"), store.get("k"))
        assertEquals(1, fake.count("addGenericPassword"), "one key, created by the second call")
    }

    @Test
    fun `a failure of the second resolution, under the locks, is typed too and releases both locks`() = withRig {
        // Calls: 1 = before the lock, 2 = under the lock. The healthy store is written first by an honest store.
        assertEquals(StorageResult.Success(Unit), store().put("k", "v"))
        val resolver = FlakyResolver(setOf(2, 4), { SecurityException("denied (test)") })
        val store: JvmSecureKeyValueStorage = store(resolver = resolver)
        val before: ByteArray = dataFile().readBytes()

        assertIs<StorageError.OperationFailed>(failure(store.get("k")))
        assertIs<StorageError.OperationFailed>(failure(store.put("k", "w")))
        assertContentEquals(before, dataFile().readBytes(), "a failed resolution never writes or quarantines")
        assertEquals(emptyList(), quarantined())
        assertEquals(StorageResult.Success("v"), store.get("k"), "both locks were released: the next call runs")
    }

    @Test
    fun `a path that keeps moving between the two resolutions answers Unavailable after three tries and writes nothing`() = withRig {
        val calls = AtomicInteger()
        val moving = StorePathResolver { file: File ->
            val real: RealStorePath = StorePathResolver.real.resolve(file)
            // Odd calls (before the lock) see the store file, even calls (under the lock) a file whose folded key differs.
            if (calls.incrementAndGet() % 2 == 1) real else RealStorePath(real.directory, "elsewhere.kmptoolkit.securestorage.bin")
        }

        val error: StorageError = failure(store(resolver = moving).put("k", "v"))

        assertIs<StorageError.Unavailable>(error)
        assertEquals(EncryptedFileGuard.MAX_PATH_TRIES * 2, calls.get(), "three tries, two resolutions each")
        assertEquals(0, fake.calls.size)
        assertTrue(fileNames().none { it.endsWith(".bin") }, "no store file: ${fileNames()}")
    }

    // ---- M2: a zero-entry store without its item ----

    @Test
    fun `a file with entries and no item stays Unavailable without UI, byte-identical, with no delete and no quarantine`() = withRig {
        val before: ByteArray = crashState(withEntries = true)
        val deletes: Int = fake.count("deleteGenericPassword")
        val quiet: JvmSecureKeyValueStorage = store(options = quiet(), cache = OsKeyCache())

        assertIs<StorageError.Unavailable>(failure(quiet.get("k")))
        assertIs<StorageError.Unavailable>(failure(quiet.put("k", "w")))
        assertIs<StorageError.Unavailable>(failure(quiet.ensureKey()))

        assertContentEquals(before, dataFile().readBytes())
        assertEquals(deletes, fake.count("deleteGenericPassword"), "no item was deleted")
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun `a zero-entry crash state without UI is repaired by ensureKey and by put, and a get answers null`() = withRig {
        crashState(withEntries = false)
        val quiet: JvmSecureKeyValueStorage = store(options = quiet(), cache = OsKeyCache())

        assertEquals(StorageResult.Success(null), quiet.get("k"))
        assertEquals(StorageResult.Success(Unit), quiet.ensureKey(), "ensureKey creates the key, losing nothing")
        assertEquals(setOf(macKey()), fake.macItems.keys)
        assertEquals(StorageResult.Success(Unit), quiet.put("k", "w"))
        assertEquals(StorageResult.Success("w"), store(options = quiet(), cache = OsKeyCache()).get("k"))
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun `without UI a refused delete or add during the repair fails closed and restores the previous file`() = withRig {
        val before: ByteArray = crashState(withEntries = false)
        val rows: List<Triple<String, Int, (StorageError) -> Boolean>> = listOf(
            Triple("deleteGenericPassword", -25308, { e -> e is StorageError.Unavailable }),
            Triple("deleteGenericPassword", -25293, { e -> e is StorageError.AccessDenied && e.platformCode == -25293 }),
            Triple("deleteGenericPassword", -128, { e -> e is StorageError.AccessDenied && e.platformCode == -128 }),
            Triple("addGenericPassword", -25299, { e -> e is StorageError.Unavailable }),
        )
        for ((function: String, status: Int, matches: (StorageError) -> Boolean) in rows) {
            fake.forcedStatus.clear()
            fake.forcedStatus[function] = status
            val adds: Int = fake.count("addGenericPassword")
            val store: JvmSecureKeyValueStorage = store(options = quiet(), cache = OsKeyCache())

            val error: StorageError = failure(store.put("k", "w"))

            assertTrue(matches(error), "$function $status answered $error")
            assertContentEquals(before, dataFile().readBytes(), "$function $status: the previous file was restored")
            assertTrue(fake.macItems.isEmpty(), "$function $status: no item was added")
            if (function == "deleteGenericPassword") assertEquals(adds, fake.count("addGenericPassword"), "$function $status: add never ran")
            assertEquals(emptyList(), quarantined())
        }
    }

    @Test
    fun `a locked keychain on the copy is not a lost key even for a zero-entry file`() = withRig {
        val before: ByteArray = crashState(withEntries = false)
        fake.forcedStatus["copyGenericPassword"] = -25308
        val store: JvmSecureKeyValueStorage = store(options = quiet(), cache = OsKeyCache())

        assertIs<StorageError.Unavailable>(failure(store.get("k")))
        assertIs<StorageError.Unavailable>(failure(store.put("k", "v")))

        assertContentEquals(before, dataFile().readBytes())
        assertEquals(0, fake.count("addGenericPassword") - 1, "only the first creation ever added an item")
    }

    @Test
    fun `an entry written by another instance between the fetch and the decision is never lost to the repair`() = withRig {
        crashState(withEntries = false)
        val other = NativeExecutor()
        try {
            val interactive: JvmSecureKeyValueStorage = store(executor = other, cache = OsKeyCache())
            val fired = AtomicBoolean(false)
            fake.before = { name: String ->
                if (name == "copyGenericPassword" && fired.compareAndSet(false, true)) {
                    // While the quiet store's fetch is in flight and holds no lock, another instance creates a key and an entry.
                    assertEquals(StorageResult.Success(Unit), runBlocking { interactive.put("k", "v") })
                }
            }
            val quiet: JvmSecureKeyValueStorage = store(options = quiet(), cache = OsKeyCache())

            assertEquals(StorageResult.Success("v"), quiet.get("k"), "the quiet store re-reads the changed file and uses the new key")
            assertEquals(1, fake.macItems.size)
            assertEquals(emptyList(), quarantined())
        } finally {
            other.close()
        }
    }

    // ---- m1: the seam's accessors ----

    private class BrokenAccessors(private val error: () -> Throwable) : NativeKeyStoreBinding {
        val calls: AtomicInteger = AtomicInteger()
        override fun macOs(): MacKeychainCalls = fail()
        override fun windows(): DpapiCalls = fail()
        override fun linux(): SecretServiceCalls = fail()
        private fun fail(): Nothing {
            calls.incrementAndGet()
            throw error()
        }
    }

    @Test
    fun `an accessor that throws is Unsupported for every OS, memoised, and never thrown from the store or the probe`() {
        val errors: List<() -> Throwable> = listOf(
            { NoSuchMethodError("accessor (test)") },
            { UnsatisfiedLinkError("accessor (test)") },
            { IllegalCallerException("accessor (test)") },
            { SecurityException("accessor (test)") },
            { IllegalStateException("accessor (test)") },
        )
        for (os: DesktopOs in DesktopOs.entries) for (error: () -> Throwable in errors) {
            val broken = BrokenAccessors(error)
            val source = FixedBindingSource(BindingSelection.Selected(broken, os, BindingKind.FAKE))
            withRig(os) {
                val store: JvmSecureKeyValueStorage = store(source = source)
                repeat(2) { round: Int ->
                    val answered: StorageError = failure(store.get("k"))
                    assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, assertIs<StorageError.Unsupported>(answered, "$os $round").reason)
                }
                assertIs<StorageError.Unsupported>(failure(store.clear()))
                assertEquals(1, broken.calls.get(), "$os: the accessor is asked once, the answer is kept")
                assertEquals(emptyList(), fileNames())
                val probe = DesktopKeyStoreProbe(source, executor, SecureStoreEnvironment(getenv = { null }, exists = { false }))
                val probed: StorageError = failure(probe.probe(DesktopSecureStorageOptions()))
                assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, assertIs<StorageError.Unsupported>(probed, "$os probe").reason)
            }
        }
    }

    // ---- m2: one provider for a re-key with the same key object ----

    @Test
    fun `changeKey with the same passphrase object asks the provider once, and with a new object each provider once`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-round4-rekey").toFile()
        try {
            runBlocking {
                withTimeout(HANG_BOUND) {
                    val calls = AtomicInteger()
                    val passphrase = AppEncryptionKey.Passphrase({ calls.incrementAndGet(); "correct horse".toCharArray() })
                    val store = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, passphrase, Dispatchers.IO)
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
                    val unlocked = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, passphrase, Dispatchers.IO)
                    calls.set(0)

                    assertEquals(StorageResult.Success(Unit), unlocked.changeKey(passphrase))
                    assertEquals(1, calls.get(), "one provider call for one changeKey with the bound key object")
                    assertEquals(StorageResult.Success("v"), unlocked.get("k"))

                    val otherCalls = AtomicInteger()
                    val other = AppEncryptionKey.Passphrase({ otherCalls.incrementAndGet(); "another one".toCharArray() })
                    calls.set(0)
                    val fresh = JvmAppKeyEncryptedKeyValueStorage("rekey", directory, passphrase, Dispatchers.IO)
                    assertEquals(StorageResult.Success(Unit), fresh.changeKey(other))
                    assertEquals(1, calls.get(), "the current key is verified with its own provider, once")
                    assertEquals(1, otherCalls.get(), "the new key's provider is asked once")
                    assertEquals(StorageResult.Success("v"), JvmAppKeyEncryptedKeyValueStorage("rekey", directory, other, Dispatchers.IO).get("k"))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- R2: the read limit bounds the write side ----

    @Test
    fun `a put that would make the store pass the limit is refused before anything is written, the store stays whole and usable`() = withRig {
        val limit = 4_096L
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(Unit), store.put("a", "x".repeat(2_000)))
        val before: ByteArray = dataFile().readBytes()

        val refused: StorageError = failure(store.put("b", "y".repeat(2_500)))

        assertEquals("b", assertIs<StorageError.InvalidValue>(refused).key)
        assertContentEquals(before, dataFile().readBytes(), "the refused put wrote nothing")
        assertEquals(emptyList(), quarantined())
        assertEquals(StorageResult.Success("x".repeat(2_000)), store.get("a"), "the store was not damaged and is read back")
        assertEquals(StorageResult.Success(null), store.get("b"))
        // Replacing the entry with a smaller value, and a second put that fits, still work.
        assertEquals(StorageResult.Success(Unit), store.put("a", "z".repeat(100)))
        assertEquals(StorageResult.Success(Unit), store.put("b", "y".repeat(2_500)))
        assertEquals(StorageResult.Success("y".repeat(2_500)), store(maxStoreBytes = limit, cache = OsKeyCache()).get("b"))
        assertEquals(emptyList(), quarantined())
    }

    @Test
    fun `a first put that cannot fit creates no key, no store file and no OS item`() = withRig {
        val limit = 4_096L
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)

        // Longer than the limit in characters, and an entry that alone passes it: refused before any lock; an entry that fits
        // alone but not with the 57-byte header of the file (4 026 characters: 4 061 + 57 > 4 096): refused after the key is prepared, before it is created.
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(limit.toInt() + 1))))
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(limit.toInt() - 10))))
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(limit.toInt() - 70))))

        assertTrue(fileNames().none { it.endsWith(".bin") }, "no store file: ${fileNames()}")
        assertTrue(fake.macItems.isEmpty(), "no item")
        assertEquals(0, fake.count("addGenericPassword"))
        assertEquals(StorageResult.Success(Unit), store.put("k", "fits"), "the store is usable afterwards")
    }

    @Test
    fun `the app-key store refuses a put over the limit and a re-key that would grow the file over it`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-round4-limit").toFile()
        try {
            runBlocking {
                withTimeout(HANG_BOUND) {
                    val limit = 1_000L
                    val raw = AppEncryptionKey.Raw { ByteArray(32) { it.toByte() } }
                    val store = JvmAppKeyEncryptedKeyValueStorage("lim", directory, raw, Dispatchers.IO, maxStoreBytes = limit)
                    // Raw header 68 + key-check 28 + count 4 + entry (2 + 1 + 12 + 4 + 16 + value) = 135 + value: 995 bytes.
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(860)))
                    val file = File(directory, directory.list()!!.single { it.endsWith(".bin") })
                    assertEquals(995L, file.length(), "the arithmetic of the test")
                    val before: ByteArray = file.readBytes()

                    assertIs<StorageError.InvalidValue>(failure(store.put("j", "w".repeat(500))))
                    assertContentEquals(before, file.readBytes())

                    // A passphrase header is 20 bytes longer than a raw key header: 1 015 bytes, over the limit.
                    val passphrase = AppEncryptionKey.Passphrase({ "correct horse".toCharArray() })
                    val refused: StorageError = failure(store.changeKey(passphrase))
                    assertEquals(StorageOperation.CHANGE_KEY, assertIs<StorageError.OperationFailed>(refused).operation)
                    assertContentEquals(before, file.readBytes(), "the refused re-key wrote nothing")
                    assertEquals(StorageResult.Success("v".repeat(860)), store.get("k"), "the old key still opens the store")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- R3: a rollback that fails is never silent ----

    private fun failingMove(restoreOnly: Boolean, real: FileAccess = createFileAccess()): FileAccess = object : FileAccess by real {
        override fun move(from: String, to: String, options: MoveOptions, cancellation: io.github.jamal_wia.kmptoolkit.files.CancellationCheck): FileResult<Unit> =
            if (restoreOnly && ".undecryptable-" in from && ".undecryptable-" !in to) FileResult.Failure(FileError.Io(to, cause = IOException("move back refused (test)")))
            else real.move(from, to, options, cancellation)
    }

    @Test
    fun `a rollback that cannot move the quarantined file back answers OperationFailed naming the quarantine and keeps the user's bytes there`() = withRig {
        val original: ByteArray = crashState(withEntries = true)
        fake.forcedStatus["addGenericPassword"] = -25299
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache(), files = failingMove(restoreOnly = true))

        val error: StorageError = failure(store.put("b", "2"))

        val failed = assertIs<StorageError.OperationFailed>(error, "the original -25299 (Unavailable) must not hide a store that is no longer as it was")
        assertEquals(StorageOperation.PUT, failed.operation)
        assertTrue(failed.cause?.message.orEmpty().contains(".undecryptable-"), "the cause names the quarantine file: ${failed.cause}")
        val kept: String = quarantined().single()
        assertContentEquals(original, File(directory, kept).readBytes(), "the user's file is intact under its quarantine name")
        fake.forcedStatus.clear()
        assertEquals(StorageResult.Success(null), store(cache = OsKeyCache()).get("a"), "what the user sees: the store reads empty (a crash state) until the next put")
    }

    @Test
    fun `a created file that cannot be deleted does not keep the user's data in quarantine`() = withRig {
        val original: ByteArray = crashState(withEntries = true)
        fake.forcedStatus["addGenericPassword"] = -25299
        val real: FileAccess = createFileAccess()
        val stubborn = object : FileAccess by real {
            override fun delete(path: String): FileResult<Unit> = FileResult.Failure(FileError.Io(path, cause = IOException("delete refused (test)")))
        }
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache(), files = stubborn)

        assertIs<StorageError.Unavailable>(failure(store.put("b", "2")), "the original error: the rollback worked")

        assertContentEquals(original, dataFile().readBytes(), "the quarantined file was renamed over the empty file in one step")
        assertEquals(emptyList(), quarantined())
        assertFalse(fileNames().any { it.contains("tmp") && it.endsWith(".bin") })
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
