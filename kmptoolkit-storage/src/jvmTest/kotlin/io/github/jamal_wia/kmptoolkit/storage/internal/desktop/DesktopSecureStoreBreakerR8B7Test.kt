package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Breaker round 7 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-7), against task/sto-desktop 481e9c13: the
 * round-9 fixes B6-B1 (`StoreIdentity.of` refuses a stored name without the `.bin` suffix) and B6-m1 (the dangling-link check also
 * asks the store's own configured name), as they reach the OS-key store.
 *
 * - B6-B1, the other side of the refusal: 05 and D-P14 say the refusal is reachable "only through a link you made". On a volume
 *   that folds case (APFS and HFS+ by default, NTFS, ext4 casefold) a file whose suffix is spelled `.BIN` IS the store's own
 *   configured file, no link involved, and its stored name does not end in `.bin` byte for byte. The 05 table says what a store
 *   does with a file at its path that does not parse (`put` quarantines it and creates a new key) and that `clear()` is the way out
 *   of an unusable store; both must still hold for that file. Beyond it, every spelling a link can reach: no suffix, a doubled
 *   suffix, a suffix with a trailing space, look-alike letters, a chain of links; the doubled suffix must neither be refused nor
 *   collide with the store whose name it extends.
 * - B6-m1, the races around the second name: the store directory itself a link, the target deleted and the link re-pointed to a
 *   user's file before the read, the target re-created between the read and the check, and a store directory that is a dangling
 *   link (not run in round 6).
 *
 * Fake binding only. The link tests skip off POSIX; the case tests skip on a volume that does not fold case.
 */
class DesktopSecureStoreBreakerR8B7Test {

    private val noUi = DesktopSecureStorageOptions(allowUserInteraction = false)

    private fun assertOperationFailed(outcome: Result<StorageResult<*>>, operation: StorageOperation, key: String?, label: String) {
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
        val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: expected OperationFailed, was $error")
        assertEquals(operation, failed.operation, "$label: operation")
        assertEquals(key, failed.key, "$label: key")
    }

    private fun quarantined(root: File): List<String> =
        Files.walk(root.toPath()).use { paths -> paths.iterator().asSequence().toList().map { it.fileName.toString() }.filter { ".undecryptable-" in it } }

    /** Whether [directory] is on a volume that folds case (one file for two spellings). */
    private fun foldsCase(directory: File): Boolean {
        val probe = File(directory, "b7-probe.bin")
        probe.writeText("probe")
        val folds: Boolean = File(directory, "B7-PROBE.BIN").exists()
        Files.delete(probe.toPath())
        return folds
    }

    /** The store file of [rig] spelled with an upper-case suffix: on a volume that folds case, the same file. */
    private fun upperSuffix(rig: SecureTestRig): File = File(rig.directory, rig.dataFile().name.removeSuffix(".bin") + ".BIN")

    // ---- B6-B1: the refusal without a link ----

    /**
     * A file of the user's (not a store) at the store's own path, its suffix spelled `.BIN`, on a volume that folds case: there is
     * no link anywhere, the configured name and that file are one file. The 05 table, row "file does not parse": `put` quarantines
     * it and creates a new key. Red on: `OperationFailed` with the cause "resolves to a name without the .bin suffix" (the round-9
     * refusal compares the stored name byte for byte), which never heals, for a situation 05 and D-P14 call "reachable only through
     * a link". Run for the three OS backends (the path resolution is the host's).
     */
    @Test
    fun `a file at the store's own path whose suffix differs only in case is treated like any unusable file`() {
        runBlocking {
            withTimeout(HANG_BOUND) {
                val broken: MutableList<String> = mutableListOf()
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        Assume.assumeTrue("needs a volume that folds case", foldsCase(rig.directory))
                        val foreign: File = upperSuffix(rig)
                        foreign.writeText("the user's file, not a store")
                        assertTrue(rig.dataFile().exists(), "$os: control, the configured name and the .BIN file are one file")
                        assertFalse(Files.isSymbolicLink(rig.dataFile().toPath()), "$os: control, no link is involved")
                        val outcome: Result<StorageResult<Unit>> = runCatching { rig.store(options = noUi).put("a", "1") }
                        val readBack: Result<StorageResult<String?>> = runCatching { rig.store(cache = OsKeyCache()).get("a") }
                        if (outcome.getOrNull() != StorageResult.Success(Unit) || readBack.getOrNull() != StorageResult.Success("1")) {
                            broken += "$os: put answered ${outcome.getOrNull() ?: outcome.exceptionOrNull()} " +
                                "(cause ${((outcome.getOrNull() as? StorageResult.Failure)?.error as? StorageError.OperationFailed)?.cause?.message}), " +
                                "read back ${readBack.getOrNull()}, quarantined ${quarantined(rig.directory).size}, files ${rig.fileNames()}"
                        }
                    }
                }
                assertEquals(emptyList(), broken, "a put over an unusable file at the store's own path must quarantine it and create (05 table); no link is involved")
            }
        }
    }

    /**
     * A VALID store whose file was renamed by a case-only change of the suffix (`rig...bin` to `rig...BIN`; on a volume that folds
     * case it is still the store's own file, no link). Whatever `get` answers for the old entries, the store must not be locked for
     * good: 05 names `clear()` the way out of an unusable store, so `clear()` and then a `put` must work and read back. Red on: every
     * operation after `clear()` still `OperationFailed` (the file keeps its header, the identity is refused on each round), so the
     * app has no way at all through the API to make the store usable again.
     */
    @Test
    fun `a store whose suffix was renamed only in case is never locked out for good`() {
        runBlocking {
            withTimeout(HANG_BOUND) {
                val broken: MutableList<String> = mutableListOf()
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        Assume.assumeTrue("needs a volume that folds case", foldsCase(rig.directory))
                        assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                        // A case-only rename in two steps (rename(2) of one file to a name the volume folds alike is a no-op).
                        val aside = File(rig.directory, "aside.tmp-b7")
                        assertTrue(rig.dataFile().renameTo(aside) && aside.renameTo(upperSuffix(rig)), "$os: control, the case-only rename worked")
                        assertTrue(rig.fileNames().any { it.endsWith(".BIN") }, "$os: control, the volume stores the new spelling: ${rig.fileNames()}")
                        val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        val get: StorageResult<String?> = store.get("k")
                        val clear: StorageResult<Unit> = store.clear()
                        val put: StorageResult<Unit> = store.put("k2", "v2")
                        val read: StorageResult<String?> = store.get("k2")
                        val ensure: StorageResult<Unit> = store.ensureKey()
                        if (clear != StorageResult.Success(Unit) || put != StorageResult.Success(Unit) || read != StorageResult.Success("v2") || ensure != StorageResult.Success(Unit)) {
                            broken += "$os: get $get, clear $clear, put $put, read $read, ensureKey $ensure, files ${rig.fileNames()}"
                        }
                    }
                }
                assertEquals(emptyList(), broken, "a store file reached without any link must not be refused for good: clear() is the documented way out")
            }
        }
    }

    // ---- B6-B1: every spelling a link can reach ----

    /**
     * The store file is a link (or a chain of two links) to a user's file elsewhere whose name does not end in `.bin` byte for byte:
     * no suffix, a suffix with a trailing space, look-alike letters (fullwidth, dotless i), a combining mark after the suffix. Each
     * has no identity: `put` answers `OperationFailed` before any native call (`ensureKey` over that unparsable file is
     * `Undecryptable(null)` by the 05 table), the user's file is byte-identical and
     * never quarantined, the links stay. Control: the same rig makes a store at an ordinary name.
     */
    @Test
    fun `every spelling without the exact suffix behind a link is refused before any native call and moves nothing`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                val targets: List<String> = listOf("t", "t.bin ", "t.ｂｉｎ", "t.bın", "t.biń", "t.bi")
                for (chain: Boolean in listOf(false, true)) {
                    for (name: String in targets) {
                        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                            val label = "target '${name.map { if (it.code < 128) it.toString() else "U+%04X".format(it.code) }.joinToString("")}'${if (chain) " via a chain" else ""}"
                            val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                            val target = File(elsewhere, name)
                            target.writeText("the user's file")
                            val before: ByteArray = target.readBytes()
                            if (chain) {
                                val hop = File(elsewhere, "hop.bin")
                                Files.createSymbolicLink(hop.toPath(), target.toPath())
                                Files.createSymbolicLink(rig.dataFile().toPath(), hop.toPath())
                            } else {
                                Files.createSymbolicLink(rig.dataFile().toPath(), target.toPath())
                            }
                            val store = rig.store(options = noUi)
                            assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", "$label put")
                            // ensureKey over a file that does not parse is Undecryptable(null) by the 05 table: no identity is needed for it.
                            assertEquals(StorageResult.Failure(StorageError.Undecryptable(null)), store.ensureKey(), "$label ensureKey")
                            assertEquals(emptyList(), rig.fake.callNames, "$label: a native call before the refusal")
                            assertContentEquals(before, target.readBytes(), "$label: the user's file changed")
                            assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                            assertTrue(Files.isSymbolicLink(rig.dataFile().toPath()), "$label: the link was replaced")
                            assertEquals(StorageResult.Success(Unit), rig.store(name = "control").put("a", "1"), "$label: control, a store at an ordinary name works")
                        }
                    }
                }
            }
        }
    }

    /**
     * A link to a user's file whose name is another store's file name plus `.bin` (`<b>.bin.bin` next to store B's `<b>.bin`): the
     * name ends in the suffix, so it has an identity, the service `<b>.bin`, which differs from B's `<b>`. A's put quarantines the
     * user's file and creates A's store there; B (cold and warm) must still read its value and keep its item. Red on: a collision
     * (B's item replaced) or a refusal of a name that does end in `.bin`.
     */
    @Test
    fun `a doubled suffix behind a link has its own identity and never touches the store whose name it extends`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val b = rig.store(name = "b")
                        assertEquals(StorageResult.Success(Unit), b.put("k", "v-b"), "$os: control, store B exists")
                        val doubled = File(rig.directory, rig.dataFile(name = "b").name + ".bin")
                        doubled.writeText("the user's file, not a store")
                        val other: File = File(rig.directory, "other").apply { mkdirs() }
                        Files.createSymbolicLink(rig.dataFile(name = "a", dir = other).toPath(), doubled.toPath())
                        val items: Int = rig.fake.macItems.size + rig.fake.secretItems.size
                        assertEquals(StorageResult.Success(Unit), rig.store(name = "a", dir = other).put("x", "1"), "$os: a name that ends in .bin has an identity")
                        assertEquals(items + 1, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: A's create must add its own item next to B's")
                        assertEquals(StorageResult.Success("v-b"), rig.store(name = "b", cache = OsKeyCache()).get("k"), "$os: B cold after A's create")
                        assertEquals(StorageResult.Success("v-b"), b.get("k"), "$os: B warm after A's create")
                        assertEquals(StorageResult.Success("1"), rig.store(name = "a", dir = other, cache = OsKeyCache()).get("x"), "$os: A reads back")
                    }
                }
            }
        }
    }

    // ---- B6-m1: races around the configured name ----

    /** Hooks on the store file's read and on the check that follows an absent read. */
    private class B7HookedFiles(private val delegate: FileAccess, private val storeName: String) : FileAccess by delegate {
        @Volatile
        var beforeRead: (() -> Unit)? = null

        @Volatile
        var afterAbsentRead: (() -> Unit)? = null

        @Volatile
        var creates: Int = 0

        override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> {
            if (File(path).name == storeName) {
                beforeRead?.let { hook: () -> Unit ->
                    beforeRead = null
                    hook()
                }
            }
            val result: FileResult<ByteArray> = delegate.readBytes(path, maxBytes)
            if (File(path).name == storeName && result is FileResult.Failure && result.error is FileError.NotFound) {
                afterAbsentRead?.let { hook: () -> Unit ->
                    afterAbsentRead = null
                    hook()
                }
            }
            return result
        }

        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            if (options.existing == ExistingFile.Fail) creates++
            return delegate.writeAtomically(path, bytes, options)
        }
    }

    /**
     * Three races around the link at the configured name, each for `put` and `ensureKey`:
     * 1. the store DIRECTORY is a link and the store file inside it a link to a file elsewhere whose target is deleted after the
     *    round resolved the path (the configured name is asked through the directory link);
     * 2. the target is deleted AND the link re-pointed to an existing user's file before the read;
     * 3. the target is deleted before the read and re-created as a user's file between the absent read and the check.
     * In each: `OperationFailed`, nothing created at the far end, no create attempted, the user's files byte-identical and never
     * quarantined, the link kept, no native call.
     */
    @Test
    fun `races around a link at the configured name never create at the far end and never touch a user's file`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (race: Int in 1..3) {
                    for (operation: StorageOperation in listOf(StorageOperation.PUT, StorageOperation.ENSURE_KEY)) {
                        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                            val label = "race $race, $operation"
                            val realDirectory: File = File(rig.directory, "real").apply { mkdirs() }
                            val storeDirectory: File = if (race == 1) {
                                File(rig.directory, "dirlink").also { Files.createSymbolicLink(it.toPath(), realDirectory.toPath()) }
                            } else {
                                realDirectory
                            }
                            val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                            val target = File(elsewhere, "target.bin").apply { writeText("about to vanish") }
                            val userFile = File(elsewhere, "user.bin").apply { writeText("the user's other file") }
                            val userBytes: ByteArray = userFile.readBytes()
                            val link: Path = File(realDirectory, rig.dataFile().name).toPath()
                            Files.createSymbolicLink(link, target.toPath())
                            val hooked = B7HookedFiles(createFileAccess(), target.name)
                            hooked.beforeRead = {
                                Files.delete(target.toPath())
                                if (race == 2) {
                                    Files.delete(link)
                                    Files.createSymbolicLink(link, userFile.toPath())
                                }
                            }
                            if (race == 3) hooked.afterAbsentRead = { target.writeText("re-created by the user") }
                            val store = rig.store(dir = storeDirectory, options = noUi, files = hooked)
                            val outcome: Result<StorageResult<Unit>> =
                                runCatching { if (operation == StorageOperation.PUT) store.put("a", "1") else store.ensureKey() }
                            assertNull(hooked.beforeRead, "$label: control, the hook ran inside the round")
                            assertOperationFailed(outcome, operation, if (operation == StorageOperation.PUT) "a" else null, label)
                            assertEquals(0, hooked.creates, "$label: a create was attempted")
                            if (race == 3) {
                                assertEquals("re-created by the user", target.readText(), "$label: the re-created file was touched")
                            } else {
                                assertFalse(target.exists(), "$label: the store created its file at the far end of the link")
                            }
                            assertContentEquals(userBytes, userFile.readBytes(), "$label: the user's other file changed")
                            assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                            assertTrue(Files.isSymbolicLink(link), "$label: the link was replaced")
                            assertEquals(emptyList(), rig.fake.callNames, "$label: a native call was made")
                        }
                    }
                }
            }
        }
    }

    /**
     * The store DIRECTORY is a dangling link (to a directory that does not exist). Reads, `remove` and `clear()` answer `Success`
     * with nothing created (05: a store whose directory does not exist holds nothing); `put` and `ensureKey` fail typed and create
     * nothing at the far end (D-P11: nothing is ever created at the far end of a link): no directory, no lock file, no item, the
     * link kept. Red on: the missing directory created through the link, or an exception.
     */
    @Test
    fun `a store directory that is a dangling link creates nothing at the far end`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val gone = File(rig.directory, "gone")
                        val dirLink = File(rig.directory, "dirlink")
                        Files.createSymbolicLink(dirLink.toPath(), gone.toPath())
                        val store = rig.store(dir = dirLink, options = noUi)
                        assertEquals(StorageResult.Success(null), store.get("a"), "$os: get")
                        assertEquals(StorageResult.Success(Unit), store.remove("a"), "$os: remove")
                        assertEquals(StorageResult.Success(Unit), store.clear(), "$os: clear")
                        val put: Result<StorageResult<Unit>> = runCatching { store.put("a", "1") }
                        val ensure: Result<StorageResult<Unit>> = runCatching { store.ensureKey() }
                        assertTrue(put.isSuccess && put.getOrThrow() is StorageResult.Failure, "$os: put must fail typed, was ${put.getOrNull() ?: put.exceptionOrNull()}")
                        assertTrue(ensure.isSuccess && ensure.getOrThrow() is StorageResult.Failure, "$os: ensureKey must fail typed, was ${ensure.getOrNull() ?: ensure.exceptionOrNull()}")
                        assertFalse(gone.exists(), "$os: the missing directory was created through the link")
                        assertTrue(Files.isSymbolicLink(dirLink.toPath()), "$os: the directory link was replaced")
                        assertEquals(0, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: an item was made")
                    }
                }
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
