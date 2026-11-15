package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.ProviderException
import java.security.SecureRandom as JcaSecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * A cipher random source for breaker round 6 of phase B: deterministic (`Random(seed)`, the parent generator is never used),
 * it keeps every 32-byte array it fills (the data keys `EnvelopeCipher.newDataKey` makes, so a test holds the very array the
 * library uses), a copy of each taken at draw time (the control that the key was live), and counts every draw. From the
 * [failFrom]-th draw on, a draw throws `ProviderException` (an entropy source that cannot be read).
 */
internal class B6RecordingRandom(private val seed: Int) : JcaSecureRandom() {
    private val source: Random = Random(seed)
    val dataKeys: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()
    val drawnCopies: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()
    val draws: AtomicInteger = AtomicInteger()

    @Volatile
    var failFrom: Int = Int.MAX_VALUE

    @Synchronized
    override fun nextBytes(bytes: ByteArray) {
        val draw: Int = draws.incrementAndGet()
        if (draw >= failFrom) throw ProviderException("the entropy source cannot be read (breaker B6, seed=$seed)")
        source.nextBytes(bytes)
        if (bytes.size == SecureFileFormat.DATA_KEY_BYTES) {
            dataKeys += bytes
            drawnCopies += bytes.copyOf()
        }
    }

    /** Indexes of data keys that are still not all zero. */
    fun liveKeys(): List<Int> = dataKeys.withIndex().filter { (_, key: ByteArray) -> key.isLiveB6() }.map { it.index }
}

internal fun ByteArray.isLiveB6(): Boolean = any { it != 0.toByte() }

/**
 * Breaker round 6 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-6), against task/sto-desktop 29627aaf: the
 * round-8 fixes B5-m1 (a dangling link at the store path) and B5-m2 (a key the library made is wiped on every way out), and
 * the ownership rule of `NativeCaller.callOwning` as it reaches the stores.
 *
 * - B5-m1, beyond the breaker's and the executor's single dangling link: link chains, relative links, links into directories
 *   that do not exist; a link that APPEARS between the dangling-link check and the create; a link that is REMOVED or
 *   RE-POINTED between the read and the check; and the plain store, which must replace a link and never write through it.
 *   The contract (05 "A store file that is a symbolic link whose target does not exist", D-P11): no write ever creates
 *   anything at the far end of a link, and a write answers `OperationFailed` at once, never the retry-later `Unavailable`.
 * - B5-m2 through native failures: an `addGenericPassword`, `store` or `protect` that throws, a macOS add that is still running
 *   when its caller leaves, a Windows create cancelled while its DPAPI call is QUEUED behind another store's call. Each test
 *   holds the arrays (a recording random source, a binding that keeps the very array it was handed) with a control that the
 *   key existed and was live while the call used it.
 *
 * Fake binding only, POSIX file semantics for the link cases (they skip elsewhere).
 */
class DesktopSecureStoreBreakerR7B6Test {

    private val noUi = DesktopSecureStorageOptions(allowUserInteraction = false)

    private fun failure(result: StorageResult<*>, label: String): StorageError = assertIs<StorageResult.Failure>(result, "$label: $result").error

    private fun assertOperationFailed(outcome: Result<StorageResult<*>>, operation: StorageOperation, key: String?, label: String): StorageError.OperationFailed {
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
        val error: StorageError = failure(outcome.getOrThrow(), label)
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: expected OperationFailed, was $error")
        assertEquals(operation, failed.operation, "$label: operation")
        assertEquals(key, failed.key, "$label: key")
        return failed
    }

    /** Every symbolic link under [root] (not followed) and where it points. */
    private fun links(root: File): Map<String, Path> =
        Files.walk(root.toPath()).use { paths ->
            paths.iterator().asSequence().toList().filter { Files.isSymbolicLink(it) }.associate { root.toPath().relativize(it).toString() to Files.readSymbolicLink(it) }
        }

    /** Every name under [root] that looks like a quarantine move. */
    private fun quarantined(root: File): List<String> =
        Files.walk(root.toPath()).use { paths -> paths.iterator().asSequence().toList().map { it.fileName.toString() }.filter { ".undecryptable-" in it } }

    // ---- B5-m1: link shapes ----

    private class LinkShape(
        val label: String,
        /** Makes the shape at [store] and returns every path that a write must never create. */
        val make: (store: File, directory: File) -> List<Path>,
    )

    private fun linkShapes(): List<LinkShape> = listOf(
        LinkShape("a relative link into a directory that does not exist") { store: File, directory: File ->
            Files.createSymbolicLink(store.toPath(), Path.of("gone", "x.bin"))
            listOf(File(directory, "gone").toPath())
        },
        LinkShape("a two-hop chain whose last link dangles") { store: File, directory: File ->
            val elsewhere: File = File(directory, "elsewhere").apply { mkdirs() }
            val hop = File(directory, "hop1")
            Files.createSymbolicLink(hop.toPath(), File(elsewhere, "missing.bin").toPath())
            Files.createSymbolicLink(store.toPath(), hop.toPath())
            listOf(File(elsewhere, "missing.bin").toPath())
        },
        LinkShape("an absolute link into a directory tree that does not exist") { store: File, directory: File ->
            val target = File(File(File(directory, "nowhere"), "deeper"), "x.bin")
            Files.createSymbolicLink(store.toPath(), target.toPath())
            listOf(File(directory, "nowhere").toPath())
        },
    )

    private fun assertNothingCreated(forbidden: List<Path>, linksBefore: Map<String, Path>, root: File, label: String) {
        for (path: Path in forbidden) assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "$label: $path was created through the link")
        assertEquals(linksBefore, links(root), "$label: a link was replaced, removed or re-pointed")
    }

    /**
     * Each link shape at the store path of an OS-key store (macOS, Linux without UI, Windows) and of both app-key stores: `get`
     * reads "no file"; `put` and `ensureKey` answer `OperationFailed` at once, before any random draw, native call or provider
     * call; no hop of the chain, no target and no missing directory is created; every link is kept. Red on: a draw, a native
     * call or a provider call before the refusal (the check placed after the create step), `Unavailable` after futile rounds (a
     * chain or a relative link that `Files.isSymbolicLink` on the canonical name would not see), or anything created at the far
     * end of a link.
     */
    @Test
    fun `link chains relative links and links into missing directories refuse a write before any key and create nothing at the far end`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (shape: LinkShape in linkShapes()) {
                    for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                        SecureTestRig(os).use { rig: SecureTestRig ->
                            val label = "$os, ${shape.label}"
                            val forbidden: List<Path> = shape.make(rig.dataFile(), rig.directory)
                            val linksBefore: Map<String, Path> = links(rig.directory)
                            val random = B6RecordingRandom(SEED)
                            val store = rig.store(options = noUi, cipher = EnvelopeCipher(random))
                            assertEquals(StorageResult.Success(null), store.get("a"), "$label: get of a store whose file is a dangling link")
                            assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", "$label put")
                            assertOperationFailed(runCatching { store.ensureKey() }, StorageOperation.ENSURE_KEY, null, "$label ensureKey")
                            assertEquals(0, random.draws.get(), "$label: a random draw (a data key or a nonce) before the refusal (seed=$SEED)")
                            assertEquals(emptyList(), rig.fake.callNames, "$label: native calls before the refusal")
                            assertNothingCreated(forbidden, linksBefore, rig.directory, label)
                        }
                    }
                    for (kind: String in listOf("raw", "passphrase")) {
                        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                            val label = "$kind app-key store, ${shape.label}"
                            val name = "b6-$kind"
                            val providerCalls = AtomicInteger()
                            val key: AppEncryptionKey = if (kind == "raw") {
                                AppEncryptionKey.Raw { providerCalls.incrementAndGet(); ByteArray(32) { 6 } }
                            } else {
                                AppEncryptionKey.Passphrase(provider = { providerCalls.incrementAndGet(); "correct horse battery".toCharArray() })
                            }
                            val storeFile = File(rig.directory, "${appKeyStoreId(name)}.bin")
                            val forbidden: List<Path> = shape.make(storeFile, rig.directory)
                            val linksBefore: Map<String, Path> = links(rig.directory)
                            val random = B6RecordingRandom(SEED)
                            val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(random))
                            assertEquals(StorageResult.Success(null), store.get("a"), "$label: get")
                            assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", "$label put")
                            assertOperationFailed(runCatching { store.ensureKey() }, StorageOperation.ENSURE_KEY, null, "$label ensureKey")
                            assertEquals(0, providerCalls.get(), "$label: the app's key provider was asked before the refusal")
                            assertEquals(0, random.draws.get(), "$label: a random draw before the refusal (seed=$SEED)")
                            assertNothingCreated(forbidden, linksBefore, rig.directory, label)
                        }
                    }
                }
            }
        }
    }

    /**
     * The plain store has no create step: its first write must REPLACE the link at its own name by the store file and never
     * write through it (05: "it never creates the target"), for every link shape: no target, no missing directory, no hop is
     * created, the second hop of a chain is kept, and the value reads back. Red on: a write that follows the link (the store
     * file would land at the far end, possibly outside the store directory).
     */
    @Test
    fun `the plain store replaces a dangling link of any shape and never writes through it`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (shape: LinkShape in linkShapes()) {
                    val directory: File = Files.createTempDirectory("kmptoolkit-b6-plain").toFile()
                    try {
                        val file = File(directory, "plain.properties")
                        val forbidden: List<Path> = shape.make(file, directory)
                        val otherLinks: Map<String, Path> = links(directory) - file.name
                        val store: KeyValueStorage = JvmKeyValueStorage(file, Dispatchers.IO)
                        assertEquals(StorageResult.Success(null), store.get("a"), "${shape.label}: get")
                        assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "${shape.label}: put")
                        assertFalse(Files.isSymbolicLink(file.toPath()), "${shape.label}: the link was not replaced by the store file")
                        assertTrue(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS), "${shape.label}: the store file is not a regular file")
                        for (path: Path in forbidden) assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "${shape.label}: $path was created through the link")
                        assertEquals(otherLinks, links(directory), "${shape.label}: another link of the chain changed")
                        assertEquals(StorageResult.Success("1"), JvmKeyValueStorage(file, Dispatchers.IO).get("a"), "${shape.label}: the value reads back")
                    } finally {
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }

    /** A file access that runs one-shot hooks at the two instants of a create that a race can hit. */
    private class HookedFiles(private val delegate: FileAccess, private val storeName: String) : FileAccess by delegate {
        /** Runs once, just after a read of the store file answered "not found" (between the read and the dangling-link check). */
        @Volatile
        var afterAbsentRead: (() -> Unit)? = null

        /** Runs once, just before the no-replace write of the store file (between the dangling-link check and the create). */
        @Volatile
        var beforeCreate: (() -> Unit)? = null

        /** Runs once, just before a read of the store file (between the path resolution under the locks and the read). */
        @Volatile
        var beforeRead: (() -> Unit)? = null

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
            if (File(path).name == storeName && options.existing == ExistingFile.Fail) {
                beforeCreate?.let { hook: () -> Unit ->
                    beforeCreate = null
                    hook()
                }
            }
            return delegate.writeAtomically(path, bytes, options)
        }
    }

    /**
     * A dangling link appears at the store name AFTER the dangling-link check and before the no-replace create (another program
     * makes it): the create finds the name taken (`Raced`) and the next round must end in the new answer, `OperationFailed`, not
     * in `Unavailable` after four rounds. The data key drawn for the raced round is wiped (the plan's dispose), nothing is created
     * at the far end, no item is made (the install never ran), and the Windows store asked DPAPI once only. Red on: a retry-later
     * answer, a live key, a created target, an item.
     */
    @Test
    fun `a dangling link that appears between the check and the create ends in OperationFailed and the raced key is wiped`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val label = "$os"
                        val target = File(File(rig.directory, "appeared"), "target.bin")
                        val hooked = HookedFiles(createFileAccess(), rig.dataFile().name)
                        hooked.beforeCreate = { Files.createSymbolicLink(rig.dataFile().toPath(), target.toPath()) }
                        val random = B6RecordingRandom(SEED)
                        val store = rig.store(options = noUi, files = hooked, cipher = EnvelopeCipher(random))
                        assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", "$label put")
                        assertNull(hooked.beforeCreate, "$label: control, the link appeared inside the create")
                        assertEquals(1, random.dataKeys.size, "$label: control, the raced round drew one data key (seed=$SEED)")
                        assertTrue(random.drawnCopies.single().isLiveB6(), "$label: control, the drawn key was live")
                        assertEquals(emptyList(), random.liveKeys(), "$label: the data key of the raced round was left unwiped (seed=$SEED)")
                        assertFalse(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS), "$label: the target was created")
                        assertFalse(target.parentFile.exists(), "$label: the target's directory was created")
                        assertEquals(target.toPath(), Files.readSymbolicLink(rig.dataFile().toPath()), "$label: the link was replaced")
                        assertEquals(emptyMap(), rig.fake.macItems.toMap(), "$label: an item was made")
                        assertEquals(emptyMap(), rig.fake.secretItems.toMap(), "$label: an item was made")
                        assertEquals(0, rig.fake.count("addGenericPassword") + rig.fake.count("store"), "$label: the install ran for a raced create")
                        assertTrue(rig.fake.count("protect") <= 1, "$label: DPAPI was asked again after the race: ${rig.fake.callNames}")
                        assertEquals(listOf(rig.dataFile().name), rig.fileNames().filter { !it.endsWith(".lock") && it != "appeared" }, "$label: a temporary file was left")
                    }
                }
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val name = "b6-appear"
                    val storeFile = File(rig.directory, "${appKeyStoreId(name)}.bin")
                    val target = File(rig.directory, "appeared-target.bin")
                    val hooked = HookedFiles(createFileAccess(), storeFile.name)
                    hooked.beforeCreate = { Files.createSymbolicLink(storeFile.toPath(), target.toPath()) }
                    val random = B6RecordingRandom(SEED)
                    val store = JvmAppKeyEncryptedKeyValueStorage(
                        name, rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 5 } }, Dispatchers.IO, CrossProcessLock(),
                        files = hooked, cipher = EnvelopeCipher(random),
                    )
                    assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", "raw app-key put")
                    assertNull(hooked.beforeCreate, "raw app-key: control, the link appeared inside the create")
                    assertEquals(1, random.dataKeys.size, "raw app-key: control, one data key drawn (seed=$SEED)")
                    assertEquals(emptyList(), random.liveKeys(), "raw app-key: the data key of the raced round was left unwiped (seed=$SEED)")
                    assertFalse(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS), "raw app-key: the target was created")
                }
            }
        }
    }

    /**
     * Between the read that found no file (through a dangling link) and the dangling-link check, another program (a) removes the
     * link: the create goes ahead at the store's own name, the store works, and the old target is never created; or (b)
     * re-points it to an existing file of the user's elsewhere: the write is refused typed and the user's file is neither
     * replaced, moved aside (quarantined) nor touched, and no key is made. Red on: an exception, a created target, a user file
     * changed or quarantined, a key item for the refused write.
     */
    @Test
    fun `a dangling link removed or re-pointed between the read and the check never writes through it`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val link: Path = rig.dataFile().toPath()
                    val target = File(rig.directory, "moved-away.bin")
                    Files.createSymbolicLink(link, target.toPath())
                    val hooked = HookedFiles(createFileAccess(), rig.dataFile().name)
                    hooked.afterAbsentRead = { Files.delete(link) }
                    val store = rig.store(files = hooked)
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "removed: the create goes ahead at the store's own name")
                    assertNull(hooked.afterAbsentRead, "removed: control, the link was removed inside the round")
                    assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS), "removed: the store file is a regular file at its own name")
                    assertFalse(target.exists(), "removed: the old target was created")
                    assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("a"), "removed: the value reads back cold")
                }
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val link: Path = rig.dataFile().toPath()
                    Files.createSymbolicLink(link, File(rig.directory, "moved-away.bin").toPath())
                    val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                    val userFile = File(elsewhere, "user-notes.txt").apply { writeText("the user's own bytes") }
                    val userBytes: ByteArray = userFile.readBytes()
                    val hooked = HookedFiles(createFileAccess(), rig.dataFile().name)
                    hooked.afterAbsentRead = {
                        Files.delete(link)
                        Files.createSymbolicLink(link, userFile.toPath())
                    }
                    val random = B6RecordingRandom(SEED)
                    val store = rig.store(files = hooked, cipher = EnvelopeCipher(random))
                    val outcome: Result<StorageResult<Unit>> = runCatching { store.put("a", "1") }
                    assertTrue(outcome.isSuccess, "re-pointed: an exception escaped: ${outcome.exceptionOrNull()}")
                    assertNull(hooked.afterAbsentRead, "re-pointed: control, the link was re-pointed inside the round")
                    failure(outcome.getOrThrow(), "re-pointed: the write was refused")
                    assertContentEquals(userBytes, userFile.readBytes(), "re-pointed: the user's file was changed")
                    assertEquals(emptyList(), quarantined(rig.directory), "re-pointed: something was moved aside")
                    assertEquals(userFile.toPath(), Files.readSymbolicLink(link), "re-pointed: the link was replaced")
                    assertEquals(emptyMap(), rig.fake.macItems.toMap(), "re-pointed: an item was made")
                    assertEquals(0, random.draws.get(), "re-pointed: a key was drawn for the refused write (seed=$SEED)")
                }
            }
        }
    }

    /**
     * The identity of an OS-key store is "the real path of its file as the volume spells it" (05; plan 4.4 rules): two
     * different files are two identities, and a create of one never touches the key item of the other (round-24 B1 was the
     * directory half of this rule). The store file of B is `<dir>/b.kmptoolkit.securestorage.bin`; the store file of A, in
     * another directory, is a symbolic link to `<dir>/b.kmptoolkit.securestorage`, an existing file of the user's that is not a
     * store (its real path differs from B's by the suffix only). A's put follows the link (by design: links are followed to an
     * existing file), moves the unusable file aside and creates A's store there. B must be unaffected: its value reads back from
     * a cold instance and from the warm one. Red on: B's key item replaced by A's create (both real names map to the service
     * `b.kmptoolkit.securestorage` because the suffix is stripped only when present), which turns B into "key lost".
     */
    @Test
    fun `two store files whose real names differ only by the suffix never share one key item`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        val broken: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val b = rig.store(name = "b")
                        assertEquals(StorageResult.Success(Unit), b.put("k", "v-b"), "$os: control, store B exists")
                        val bFile: File = rig.dataFile(name = "b")
                        val bare = File(rig.directory, bFile.name.removeSuffix(".bin"))
                        bare.writeText("the user's file, not a store")
                        val otherDirectory: File = File(rig.directory, "other").apply { mkdirs() }
                        Files.createSymbolicLink(rig.dataFile(name = "a", dir = otherDirectory).toPath(), bare.toPath())
                        val a = rig.store(name = "a", dir = otherDirectory)
                        val outcome: Result<StorageResult<Unit>> = runCatching { a.put("x", "1") }
                        assertTrue(outcome.isSuccess, "$os: an exception escaped A's put: ${outcome.exceptionOrNull()}")
                        val cold: StorageResult<String?> = rig.store(name = "b", cache = OsKeyCache()).get("k")
                        val warm: StorageResult<String?> = b.get("k")
                        if (cold != StorageResult.Success("v-b") || warm != StorageResult.Success("v-b")) {
                            broken += "$os: B cold $cold, warm $warm after A's put answered ${outcome.getOrThrow()}; items ${rig.fake.macItems.keys + rig.fake.secretItems.keys}"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), broken, "a create of one store file replaced the key item of another file whose real name differs by the suffix only")
    }

    /**
     * The store path is a link to an EXISTING file elsewhere (followed by design: the store's real path is the target), and the
     * target is deleted after the round resolved the real path under the locks and before it reads the file. At the moment the
     * round decides, the store path is a symbolic link whose target does not exist: 05 says `put` then answers `OperationFailed`
     * and "neither the link nor its missing target is touched", D-P11 that nothing is created at the name of a link. Red on: the
     * store file created at the far end of the link (the dangling-link check asks the RESOLVED name, which is the target and not
     * a link, so the create goes ahead there).
     */
    @Test
    fun `a link whose target is deleted after the path was resolved never makes the store create the target`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                    val target = File(elsewhere, "target.bin").apply { writeText("a file that is about to be deleted") }
                    Files.createSymbolicLink(rig.dataFile().toPath(), target.toPath())
                    val hooked = HookedFiles(createFileAccess(), target.name)
                    hooked.beforeRead = { Files.delete(target.toPath()) }
                    val store = rig.store(options = noUi, files = hooked)
                    val outcome: Result<StorageResult<Unit>> = runCatching { store.put("a", "1") }
                    assertNull(hooked.beforeRead, "control: the target was deleted inside the round")
                    assertTrue(Files.isSymbolicLink(rig.dataFile().toPath()), "the link was replaced")
                    assertFalse(target.exists(), "the store created its file at the far end of a link whose target did not exist (put answered ${outcome.getOrNull()}, items ${rig.fake.macItems.size})")
                    assertOperationFailed(outcome, StorageOperation.PUT, "a", "put")
                }
            }
        }
    }

    // ---- B5-m2 through native failures ----

    /** The fake binding, with the secret-carrying calls intercepted: each keeps the very array it was handed and a copy of it. */
    private class CapturingBinding(private val fake: FakeNativeKeyStoreBinding) : NativeKeyStoreBinding {
        /** The arrays the store handed to a native call, as handed (the test checks they are wiped afterwards). */
        val handed: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        /** A copy of each, taken when the call ran: the control that the secret was live while the call used it. */
        val seen: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        /** The call that throws (an unexpected native failure), or `null`. */
        @Volatile
        var throwIn: String? = null

        private fun intercept(name: String, secret: ByteArray) {
            handed += secret
            seen += secret.copyOf()
            if (throwIn == name) throw RuntimeException("the native call failed (breaker B6)")
        }

        override fun macOs(): MacKeychainCalls = object : MacKeychainCalls by fake.macOs() {
            override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
                intercept("addGenericPassword", secret)
                return fake.macOs().addGenericPassword(service, account, label, secret, allowUi)
            }
        }

        override fun windows(): DpapiCalls = object : DpapiCalls by fake.windows() {
            override fun protect(data: ByteArray, entropy: ByteArray?): RawResult<ByteArray> {
                intercept("protect", data)
                return fake.windows().protect(data, entropy)
            }
        }

        override fun linux(): SecretServiceCalls = object : SecretServiceCalls by fake.linux() {
            override fun store(label: String, attributes: Map<String, String>, secret: ByteArray): SecretStatus {
                intercept("store", secret)
                return fake.linux().store(label, attributes, secret)
            }
        }
    }

    private fun SecureTestRig.source(binding: NativeKeyStoreBinding): BindingSource = FixedBindingSource(BindingSelection.Selected(binding, os, BindingKind.FAKE))

    /**
     * The secret-carrying native call of a create throws (macOS `addGenericPassword` after a successful delete, Linux `store`
     * after a successful clear, Windows `protect`): the put answers `OperationFailed(PUT, key)`, the create is rolled back (no
     * file, no item), the array handed to the call (macOS: the key copy; Linux: its base64 text made on the native thread;
     * Windows: the copy DPAPI protects) and the data key itself are all zero afterwards, and the next put works. Controls: the
     * handed array held the drawn key (or its base64) while the call ran. Red on: any live array, an orphan item, a file left.
     */
    @Test
    fun `a secret-carrying native call that throws leaves every copy of the key wiped and rolls the create back`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val failing: Map<DesktopOs, String> = mapOf(
                DesktopOs.MACOS to "addGenericPassword",
                DesktopOs.LINUX to "store",
                DesktopOs.WINDOWS to "protect",
            )
            for ((os: DesktopOs, call: String) in failing) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val label = "$os $call"
                    val capturing = CapturingBinding(rig.fake).also { it.throwIn = call }
                    val random = B6RecordingRandom(SEED)
                    val store = rig.store(options = noUi, source = rig.source(capturing), cipher = EnvelopeCipher(random))
                    assertOperationFailed(runCatching { store.put("a", "1") }, StorageOperation.PUT, "a", label)
                    assertEquals(1, capturing.handed.size, "$label: control, the call was handed one secret")
                    val drawn: ByteArray = random.drawnCopies.single()
                    val expectedSeen: ByteArray = if (os == DesktopOs.LINUX) Base64.getEncoder().encode(drawn) else drawn
                    assertContentEquals(expectedSeen, capturing.seen.single(), "$label: control, the call saw the drawn key (seed=$SEED)")
                    assertFalse(capturing.handed.single().isLiveB6(), "$label: the array handed to the failed call was left live")
                    assertEquals(emptyList(), random.liveKeys(), "$label: the data key was left unwiped (seed=$SEED)")
                    assertFalse(rig.dataFile().exists(), "$label: the create was not rolled back")
                    assertEquals(emptyMap(), rig.fake.macItems.toMap(), "$label: an item was left")
                    assertEquals(emptyMap(), rig.fake.secretItems.toMap(), "$label: an item was left")
                    capturing.throwIn = null
                    assertEquals(StorageResult.Success(Unit), store.put("a", "2"), "$label: the next put works")
                    assertTrue(capturing.handed.none { it.isLiveB6() }, "$label: a handed array of the working put was left live")
                    assertEquals(StorageResult.Success("2"), rig.store(source = rig.source(capturing), cache = OsKeyCache()).get("a"), "$label: the value reads back cold")
                }
            }
        }
        Unit
    }

    /**
     * macOS with dialogs allowed (no time limit): the caller leaves while `addGenericPassword` is running. The create is rolled
     * back and the plan's own key is wiped at once, but the copy the native call is still READING must stay live until the call
     * returns (wiping it under the reader would store a zero key), and then be wiped by the native thread. The late item the call
     * adds after the rollback must not break the next put (which runs behind it on the one native thread). Red on: the copy wiped
     * under the running call, the copy left live after it returned, a file left by the cancelled create, a failing next put.
     */
    @Test
    fun `a macOS add still running when its caller leaves keeps its copy live until it returns and the next put replaces its item`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val capturing = CapturingBinding(rig.fake)
                val inside = CountDownLatch(1)
                val release = CountDownLatch(1)
                val armed = AtomicBoolean(true)
                rig.fake.before = { name: String ->
                    if (name == "addGenericPassword" && armed.get()) {
                        inside.countDown()
                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the add" }
                    }
                }
                val random = B6RecordingRandom(SEED)
                val store = rig.store(options = DesktopSecureStorageOptions(allowUserInteraction = true), source = rig.source(capturing), cipher = EnvelopeCipher(random))
                val put = async(Dispatchers.Default) { store.put("a", "1") }
                try {
                    check(inside.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the add was never reached" }
                    assertTrue(capturing.handed.single().isLiveB6(), "control: the copy is live while the add runs")
                    assertTrue(random.dataKeys.single().isLiveB6(), "control: the plan's key is live while the add runs (seed=$SEED)")
                    put.cancel()
                    put.join()
                    assertTrue(capturing.handed.single().isLiveB6(), "the copy the running add reads was wiped under it")
                    assertEquals(emptyList(), random.liveKeys(), "the plan's own key was not wiped when its caller left (seed=$SEED)")
                    assertFalse(rig.dataFile().exists(), "the cancelled create left its file")
                } finally {
                    armed.set(false)
                    release.countDown()
                }
                assertEquals(StorageResult.Success(Unit), store.put("a", "2"), "the next put, queued behind the late add, works")
                assertEquals(2, capturing.handed.size, "control: two adds ran")
                assertTrue(capturing.handed.none { it.isLiveB6() }, "a copy was left live after its add returned")
                assertEquals(1, rig.fake.macItems.size, "one item after the second put")
                assertEquals(StorageResult.Success("2"), rig.store(source = rig.source(capturing), cache = OsKeyCache()).get("a"), "the value reads back cold")
            }
        }
        Unit
    }

    /**
     * Windows: the store's DPAPI call is QUEUED behind another store's interactive key fetch on the one native thread, and its
     * caller is cancelled. The call is withdrawn and never runs (DPAPI is never asked, then or later), and the data key the create
     * drew must be wiped (the finally of `newKey`; the copy is the caller's to wipe in `callOwning`). Control: the key is live
     * while the call is queued, and the executor has exactly the running fetch and the queued call. Red on: a live key, a DPAPI
     * call that runs after its caller left, a file left.
     */
    @Test
    fun `a Windows create cancelled while its DPAPI call is queued never runs the call and wipes the key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { mac: SecureTestRig ->
                SecureTestRig(DesktopOs.WINDOWS).use { win: SecureTestRig ->
                    val ui = DesktopSecureStorageOptions(allowUserInteraction = true)
                    assertEquals(StorageResult.Success(Unit), mac.store(options = ui).put("m", "1"), "control: the macOS store exists")
                    val busy = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    mac.fake.before = { name: String ->
                        if (name == "copyGenericPassword") {
                            busy.countDown()
                            check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                        }
                    }
                    val blocker = async(Dispatchers.Default) { mac.store(options = ui, cache = OsKeyCache()).get("m") }
                    val random = B6RecordingRandom(SEED)
                    val winStore = win.store(executor = mac.executor, cipher = EnvelopeCipher(random))
                    try {
                        check(busy.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the fetch never started" }
                        val put = async(Dispatchers.Default) { winStore.put("w", "1") }
                        while (mac.executor.pendingCalls < 2) yield()
                        assertEquals(2, mac.executor.pendingCalls, "control: the fetch runs and the DPAPI call is queued")
                        assertEquals(1, random.dataKeys.size, "control: the create drew its key (seed=$SEED)")
                        assertTrue(random.dataKeys.single().isLiveB6(), "control: the key is live while its call is queued")
                        put.cancel()
                        put.join()
                        assertEquals(emptyList(), random.liveKeys(), "the key of a create whose DPAPI call was withdrawn was left live (seed=$SEED)")
                    } finally {
                        release.countDown()
                    }
                    assertEquals(StorageResult.Success("1"), blocker.await(), "control: the blocking fetch completes")
                    assertEquals(0, win.fake.count("protect"), "a withdrawn DPAPI call ran after its caller left")
                    assertFalse(win.dataFile().exists(), "the cancelled create left a file")
                    assertEquals(StorageResult.Success(Unit), winStore.put("w", "2"), "the same Windows store works afterwards")
                    assertEquals(StorageResult.Success("2"), win.store(executor = mac.executor, cache = OsKeyCache()).get("w"), "the value reads back cold")
                }
            }
        }
        Unit
    }

    private companion object {
        const val SEED: Int = 606
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 120.seconds
    }
}
