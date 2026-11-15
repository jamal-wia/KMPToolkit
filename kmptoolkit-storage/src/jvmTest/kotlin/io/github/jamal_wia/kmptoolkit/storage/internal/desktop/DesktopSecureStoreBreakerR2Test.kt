package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Breaker round 2 of phase B of group `sto-desktop` (plan 4.4, 4.5, 4.11 and the executor's decision proposals D-P1, D-P2,
 * D-P6, D-P7; review/sto-desktop-B-breaker-2). Fake binding only, real files in a directory the test creates and deletes,
 * real threads bounded by hang timeouts only, every random choice from a fixed seed named in the message.
 *
 * Attacked: the identity derived from the real path per round (symlinked directories, a link re-pointed between calls and
 * DURING the off-lock key fetch, directory spellings that differ in case or Unicode form on one volume, a seeded storm over
 * three spellings of one store), the 256 MiB bound at exactly its limit (OS-key and app-key stores, `changeKey` that keeps
 * the size), and a cancelled create after a quarantine (the user's file must be back, never left in quarantine).
 */
class DesktopSecureStoreBreakerR2Test {

    private fun <T> withRig(os: DesktopOs = DesktopOs.MACOS, block: suspend SecureTestRig.() -> T): T =
        SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun SecureTestRig.quarantinedAnywhere(): List<String> =
        directory.walkTopDown().filter { ".undecryptable-" in it.name }.map { it.path }.toList()

    /** A symbolic link, or a skipped test where the host refuses them (Windows without the privilege). */
    private fun linkOrSkip(link: File, target: File) {
        val made: Boolean = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("the host cannot create symbolic links", made)
    }

    private fun repoint(link: File, target: File) {
        Files.delete(link.toPath())
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    // ---- identity: one real path, one identity, per round ----

    /**
     * D-P1: the identity is the real path of the store file. A directory reached through a symbolic link and through its
     * target is ONE store: one key item, one set of values, nothing quarantined. Red on: an identity from the spelled (not
     * canonical) path, which makes each spelling read the other's header as foreign ("key lost") and quarantine it.
     */
    @Test
    fun `a store reached through a symlinked directory and through its target is one store with one key`() = withRig {
        val target = File(directory, "real").also { assertTrue(it.mkdir()) }
        val link = File(directory, "link")
        linkOrSkip(link, target)

        assertEquals(StorageResult.Success(Unit), store(dir = link).put("a", "1"))
        assertEquals(StorageResult.Success(Unit), store(dir = target, cache = OsKeyCache()).put("b", "2"))

        val viaLink: JvmSecureKeyValueStorage = store(dir = link, cache = OsKeyCache())
        assertEquals(StorageResult.Success("1"), viaLink.get("a"))
        assertEquals(StorageResult.Success("2"), viaLink.get("b"))
        assertEquals(1, fake.macItems.size, "one store, one item: ${fake.macItems.keys}")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * R1 + D-P1 "derived under the lock on every call and never remembered": an instance opened through a link follows the
     * link when it is re-pointed to another directory holding ANOTHER store. It reads that store's values with that store's
     * key, its writes land there, and the first store is byte-identical. Red on: an identity, path or cached key remembered
     * across calls (the instance would answer with the old store, or read the new header as foreign and quarantine it).
     */
    @Test
    fun `an instance opened through a link follows the link to another store and never mixes the two keys`() = withRig {
        val x = File(directory, "x").also { assertTrue(it.mkdir()) }
        val y = File(directory, "y").also { assertTrue(it.mkdir()) }
        assertEquals(StorageResult.Success(Unit), store(dir = x).put("k", "x-value"))
        assertEquals(StorageResult.Success(Unit), store(dir = y).put("k", "y-value"))
        val link = File(directory, "current")
        linkOrSkip(link, x)
        val viaLink: JvmSecureKeyValueStorage = store(dir = link)
        assertEquals(StorageResult.Success("x-value"), viaLink.get("k"), "control: the link reaches x")
        val xBytes: ByteArray = dataFile(dir = x).readBytes()

        repoint(link, y)

        assertEquals(StorageResult.Success("y-value"), viaLink.get("k"), "the same instance reads y after the link moved")
        assertEquals(StorageResult.Success(Unit), viaLink.put("n", "new"))
        assertContentEquals(xBytes, dataFile(dir = x).readBytes(), "x was not touched")
        assertEquals(StorageResult.Success("new"), store(dir = y, cache = OsKeyCache()).get("n"), "the write landed in y")
        assertEquals(StorageResult.Success(null), store(dir = x, cache = OsKeyCache()).get("n"))
        assertEquals(2, fake.macItems.size, "two stores, two items: ${fake.macItems.keys}")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * The off-lock fetch is the one moment the engine is outside the lock with a decision in hand (plan 4.4: "the next
     * round re-reads the file and uses the key only if the header and key-check are the ones it was fetched for"). The link
     * is re-pointed from x to y WHILE the key of x is being fetched for a `get` and, in a second instance, for a `put`, both
     * with an empty key cache. The get must answer y's value, the put must land in y under y's key, and x must stay
     * byte-identical. Red on: a fetched key used for the file of the next round without the snapshot/identity check (x's
     * key against y's file fails its key-check = "key lost": Undecryptable for the get, a quarantine of y for the put).
     */
    @Test
    fun `a link re-pointed during the off-lock key fetch never applies the old store's key to the new store`() = withRig {
        val x = File(directory, "x").also { assertTrue(it.mkdir()) }
        val y = File(directory, "y").also { assertTrue(it.mkdir()) }
        assertEquals(StorageResult.Success(Unit), store(dir = x).put("k", "x-value"))
        assertEquals(StorageResult.Success(Unit), store(dir = y).put("k", "y-value"))
        val xBytes: ByteArray = dataFile(dir = x).readBytes()
        val link = File(directory, "current")
        linkOrSkip(link, x)

        for (operation: String in listOf("get", "put")) {
            if (operation == "put") repoint(link, x)
            val swapped = AtomicBoolean(false)
            fake.before = { name: String ->
                if (name == "copyGenericPassword" && swapped.compareAndSet(false, true)) repoint(link, y)
            }
            try {
                val viaLink: JvmSecureKeyValueStorage = store(dir = link, cache = OsKeyCache())
                if (operation == "get") {
                    assertEquals(StorageResult.Success("y-value"), viaLink.get("k"), "the get after the swap reads y")
                } else {
                    assertEquals(StorageResult.Success(Unit), viaLink.put("p", "put-after-swap"))
                }
            } finally {
                fake.before = {}
            }
            assertTrue(swapped.get(), "control: the swap happened during a fetch ($operation)")
        }

        assertContentEquals(xBytes, dataFile(dir = x).readBytes(), "x was never written")
        val yReader: JvmSecureKeyValueStorage = store(dir = y, cache = OsKeyCache())
        assertEquals(StorageResult.Success("y-value"), yReader.get("k"))
        assertEquals(StorageResult.Success("put-after-swap"), yReader.get("p"))
        assertEquals(2, fake.macItems.size, "two stores, two items: ${fake.macItems.keys}")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * D-P1 on a case-insensitive volume (macOS default APFS, Windows): two spellings of the store's DIRECTORY name one
     * directory, so they are one store (the executor's round-4 tests cover two spellings of the store NAME only). Red on:
     * a directory fingerprint from the spelled path (each spelling quarantines the other's file).
     */
    @Test
    fun `two case spellings of the store directory on a case-insensitive volume are one store`() = withRig {
        val stored = File(directory, "Data").also { assertTrue(it.mkdir()) }
        val other = File(directory, "DATA")
        assumeTrue("the volume is case-sensitive: the two spellings are two directories", other.exists())

        assertEquals(StorageResult.Success(Unit), store(dir = stored).put("a", "1"))
        assertEquals(StorageResult.Success(Unit), store(dir = other, cache = OsKeyCache()).put("b", "2"))

        val reader: JvmSecureKeyValueStorage = store(dir = other, cache = OsKeyCache())
        assertEquals(StorageResult.Success("1"), reader.get("a"))
        assertEquals(StorageResult.Success("2"), store(dir = stored, cache = OsKeyCache()).get("b"))
        assertEquals(1, fake.macItems.size, "one directory, one item: ${fake.macItems.keys}")
        assertEquals(listOf("Data"), directory.list()!!.sorted(), "the stored spelling of the directory never flips")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * D-P1 "NFC only on macOS": a directory created with an NFC name and reached through its NFD spelling (one directory on
     * APFS and HFS+) is one store. Red on: an identity that keeps the spelled form on macOS.
     */
    @Test
    fun `the NFC and NFD spellings of one directory on macOS are one store`() = withRig {
        val nfc: String = Normalizer.normalize("café-Ångström", Normalizer.Form.NFC)
        val nfd: String = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        val stored = File(directory, nfc).also { assertTrue(it.mkdir()) }
        val other = File(directory, nfd)
        assumeTrue("the volume keeps the two Unicode forms apart (not macOS)", other.exists() && Files.isSameFile(stored.toPath(), other.toPath()))

        assertEquals(StorageResult.Success(Unit), store(dir = stored).put("a", "1"))
        assertEquals(StorageResult.Success(Unit), store(dir = other, cache = OsKeyCache()).put("b", "2"))

        assertEquals(StorageResult.Success("1"), store(dir = other, cache = OsKeyCache()).get("a"))
        assertEquals(StorageResult.Success("2"), store(dir = stored, cache = OsKeyCache()).get("b"))
        assertEquals(1, fake.macItems.size, "one directory, one item: ${fake.macItems.keys}")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * A seeded storm over every spelling of ONE store this host offers (the directory, its case variant where the volume
     * folds case, a symbolic link to it), half of the instances on the shared key cache and half on their own (so keys are
     * fetched off the lock while other instances create, write and remove). Every call must succeed, every `get` must
     * answer a value somebody put for that key (or null), nothing may be quarantined, and there must be exactly one key
     * item at the end. Red on: any path through which two spellings get two identities, two locks, or a stale cached key.
     */
    @Test
    fun `a seeded storm over three spellings of one store keeps one identity, one item and every value`() = withRig {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 20261115L
        val stored = File(directory, "Store").also { assertTrue(it.mkdir()) }
        val spellings: MutableList<File> = mutableListOf(stored)
        File(directory, "STORE").takeIf { it.exists() }?.let { spellings += it }
        val link = File(directory, "alias")
        if (runCatching { Files.createSymbolicLink(link.toPath(), stored.toPath()) }.isSuccess) spellings += link
        assumeTrue("seed=$seed: this host offers only one spelling of a directory", spellings.size >= 2)

        val written: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap()
        val problems: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val start = CountDownLatch(1)
        coroutineScope {
            val workers = (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1_000 + worker)
                    val dir: File = spellings[worker % spellings.size]
                    val instance: JvmSecureKeyValueStorage = store(dir = dir, cache = if (worker % 2 == 0) cache else OsKeyCache())
                    check(start.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "seed=$seed: the start gate never opened" }
                    repeat(OPS_PER_WORKER) { step: Int ->
                        val key = "k${random.nextInt(KEYS)}"
                        when (random.nextInt(10)) {
                            in 0..4 -> {
                                val value = "w$worker-s$step"
                                written.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(value)
                                val result: StorageResult<Unit> = instance.put(key, value)
                                if (result !is StorageResult.Success) problems += "seed=$seed worker=$worker step=$step put($key) via ${dir.name}: $result"
                            }
                            in 5..7 -> when (val result: StorageResult<String?> = instance.get(key)) {
                                is StorageResult.Success -> {
                                    val value: String? = result.value
                                    if (value != null && value !in written[key].orEmpty()) {
                                        problems += "seed=$seed worker=$worker step=$step get($key) via ${dir.name} answered a value nobody put: $value"
                                    }
                                }
                                is StorageResult.Failure -> problems += "seed=$seed worker=$worker step=$step get($key) via ${dir.name}: $result"
                            }
                            else -> {
                                val result: StorageResult<Unit> = instance.remove(key)
                                if (result !is StorageResult.Success) problems += "seed=$seed worker=$worker step=$step remove($key) via ${dir.name}: $result"
                            }
                        }
                    }
                }
            }
            start.countDown()
            workers.awaitAll()
        }

        assertEquals(emptyList(), problems.take(10), "seed=$seed spellings=${spellings.map { it.name }}")
        assertEquals(emptyList(), quarantinedAnywhere(), "seed=$seed")
        assertEquals(1, fake.macItems.size, "seed=$seed: one store, one item: ${fake.macItems.keys}")
        // Quiescent: every spelling, in a new process (empty cache), reads every key without a failure, and all agree.
        val answers: List<List<StorageResult<String?>>> = spellings.map { dir: File ->
            val reader: JvmSecureKeyValueStorage = store(dir = dir, cache = OsKeyCache())
            (0 until KEYS).map { reader.get("k$it") }
        }
        answers.forEach { perSpelling: List<StorageResult<String?>> ->
            perSpelling.forEach { assertIs<StorageResult.Success<String?>>(it, "seed=$seed: $it") }
            assertEquals(answers.first(), perSpelling, "seed=$seed: every spelling reads the same values")
        }
    }

    // ---- R2 / D-P6: the bound at exactly its limit ----

    /**
     * D-P6: "a put whose encoded file would PASS the limit is refused"; the read side accepts a file of exactly the limit
     * (`readBytes(path, max)` fails only above it). So a put that makes the file exactly `maxStoreBytes` long must succeed
     * and read back in a new process without any quarantine, and one more byte must be refused with nothing written. Red
     * on: an off-by-one on either side (`>=` on the write side refuses a file the read side accepts; an encodedSize that
     * under-counts writes a file the read side quarantines on the next put).
     */
    @Test
    fun `an OS-key store file of exactly the limit is written and read back, one byte more is refused`() = withRig {
        val limit = 4_096L
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(Unit), store.put("a", "x"))
        val oneCharFile: Long = dataFile().length()
        // An ASCII value adds one byte per character: this value makes the file exactly the limit.
        val atLimit: Int = (limit - oneCharFile + 1).toInt()

        assertEquals(StorageResult.Success(Unit), store.put("a", "x".repeat(atLimit)), "a file of exactly the limit fits")
        assertEquals(limit, dataFile().length(), "the arithmetic of the test: the file is exactly the limit")
        val atLimitBytes: ByteArray = dataFile().readBytes()

        assertIs<StorageError.InvalidValue>(failure(store.put("a", "x".repeat(atLimit + 1))), "one byte over the limit")
        assertContentEquals(atLimitBytes, dataFile().readBytes(), "the refused put wrote nothing")

        val reader: JvmSecureKeyValueStorage = store(maxStoreBytes = limit, cache = OsKeyCache())
        assertEquals(StorageResult.Success("x".repeat(atLimit)), reader.get("a"), "a file of exactly the limit reads back in a new process")
        assertEquals(StorageResult.Success(Unit), reader.put("a", "small"), "and is not quarantined by the next put")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    /**
     * The same boundary for the app-key store, and `changeKey` to another raw key, which keeps the file size: a store of
     * exactly the limit must be re-keyable (the re-keyed file is exactly the limit too, so D-P6 allows it), and the new key
     * must open it in a new instance. Red on: `>=` in the changeKey bound, or a re-key that leaves the instance or the file
     * half-switched.
     */
    @Test
    fun `an app-key store of exactly the limit is written, read back and re-keyed at the same size`() {
        val directory: File = Files.createTempDirectory("kmptoolkit-breaker-r2-limit").toFile()
        try {
            runBlocking {
                withTimeout(HANG_BOUND) {
                    val limit = 1_000L
                    val first = AppEncryptionKey.Raw { ByteArray(32) { it.toByte() } }
                    val second = AppEncryptionKey.Raw { ByteArray(32) { (it + 100).toByte() } }
                    val store = JvmAppKeyEncryptedKeyValueStorage("lim", directory, first, Dispatchers.IO, maxStoreBytes = limit)
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
                    val file = File(directory, directory.list()!!.single { it.endsWith(".bin") })
                    val atLimit: Int = (limit - file.length() + 1).toInt()

                    assertEquals(StorageResult.Success(Unit), store.put("k", "v".repeat(atLimit)))
                    assertEquals(limit, file.length(), "the arithmetic of the test: the file is exactly the limit")
                    assertIs<StorageError.InvalidValue>(failure(store.put("k", "v".repeat(atLimit + 1))))

                    assertEquals(StorageResult.Success(Unit), store.changeKey(second), "a re-key at the same size fits")
                    assertEquals(limit, file.length())
                    val reopened = JvmAppKeyEncryptedKeyValueStorage("lim", directory, second, Dispatchers.IO, maxStoreBytes = limit)
                    assertEquals(StorageResult.Success("v".repeat(atLimit)), reopened.get("k"), "the new key opens the re-keyed file")
                    val stale = JvmAppKeyEncryptedKeyValueStorage("lim", directory, first, Dispatchers.IO, maxStoreBytes = limit)
                    assertIs<StorageResult.Failure>(stale.get("k"), "the old key no longer opens it")
                    assertTrue(directory.list()!!.none { ".undecryptable-" in it }, "nothing quarantined: ${directory.list()!!.toList()}")
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- R3 / D-P7: a cancelled create after a quarantine ----

    /**
     * Plan 4.4 "Create": "cancellation does the same" (rolls everything back, the store is byte-identical). A put over a
     * store whose key is lost (entries present) quarantines the file, writes the empty file and then waits on the
     * native `add`; the caller is cancelled there. The user's file must be back under its own name with its own bytes (the
     * one-step rename-back of R3 on the cancellation path), no quarantine file may remain, and once the late native call ends
     * the store must still answer typed, never crash. Red on: a rollback that skips the rename-back for a thrown
     * cancellation, or one that leaves the empty file in place.
     */
    @Test
    fun `a put cancelled while the new key is being added after a quarantine puts the user's file back`() = withRig {
        val writer: JvmSecureKeyValueStorage = store()
        assertEquals(StorageResult.Success(Unit), writer.put("k", "v"))
        fake.macItems.clear()
        val original: ByteArray = dataFile().readBytes()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.before = { name: String ->
            if (name == "addGenericPassword") {
                entered.countDown()
                check(release.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS)) { "the test never released the native add" }
            }
        }
        try {
            val victim: JvmSecureKeyValueStorage = store(cache = OsKeyCache())
            val outcome = ConcurrentHashMap<String, Any>()
            coroutineScope {
                val job = launch(Dispatchers.IO) {
                    try {
                        outcome["result"] = victim.put("b", "2")
                    } catch (cancelled: CancellationException) {
                        outcome["cancelled"] = true
                        throw cancelled
                    }
                }
                assertTrue(entered.await(LATCH_BOUND_SECONDS, TimeUnit.SECONDS), "control: the create reached the native add")
                assertTrue(fileNames().any { ".undecryptable-" in it }, "control: the user's file was in quarantine at that moment: ${fileNames()}")
                job.cancel()
                job.join()
            }

            assertTrue(outcome.containsKey("cancelled") || outcome.containsKey("result"), "the put ended: $outcome")
            assertContentEquals(original, dataFile().readBytes(), "the user's file is back under its name, byte-identical")
            assertEquals(emptyList(), fileNames().filter { ".undecryptable-" in it }, "nothing left in quarantine")
        } finally {
            release.countDown()
            fake.before = {}
        }
        // The late add may land an item that keys nothing; the store still answers typed for the lost key and stays usable.
        val after: JvmSecureKeyValueStorage = store(cache = OsKeyCache())
        assertIs<StorageError.Undecryptable>(failure(after.get("k")), "the lost key is still lost, typed")
        assertEquals(StorageResult.Success(Unit), after.put("b", "2"), "the next put quarantines and recreates as the table says")
        assertEquals(StorageResult.Success("2"), store(cache = OsKeyCache()).get("b"))
    }

    // ---- M2 / D-P2: the empty-file repair never touches a file with entries ----

    /**
     * D-P2: the non-interactive -25300 is "lost" ONLY for a zero-entry file. Here the file is empty when the key is
     * fetched, but another instance (interactive, with the key) writes an entry while the non-interactive fetch is in
     * flight, so the round that decides sees entries. Neither `ensureKey()` nor `put` may then repair (replace the file or
     * the item): the entry must survive and the answer is the conservative `Unavailable`. Variant of the executor's test
     * for `put`, extended to `ensureKey()` and checked against the item too. Red on: deciding "zero entries" from the
     * fetch-time snapshot rather than from the file of the deciding round.
     */
    @Test
    fun `an entry written during a non-interactive fetch of an empty store is never lost to the repair, for ensureKey or put`() = withRig {
        val interactive: JvmSecureKeyValueStorage = store()
        assertEquals(StorageResult.Success(Unit), interactive.put("seed", "x"))
        assertEquals(StorageResult.Success(Unit), interactive.remove("seed"))
        val itemBefore: Map<Pair<String, String>, ByteArray> = fake.macItems.mapValues { it.value.copyOf() }
        fake.forcedStatus["copyGenericPassword"] = ERR_SEC_ITEM_NOT_FOUND

        for (operation: String in listOf("ensureKey", "put")) {
            val wrote = AtomicBoolean(false)
            fake.before = { name: String ->
                if (name == "copyGenericPassword" && wrote.compareAndSet(false, true)) {
                    // Another process with the key writes an entry while this fetch runs (its own cache has the key).
                    runBlocking { check(interactive.put("during-$operation", "kept") is StorageResult.Success) }
                }
            }
            try {
                val quiet: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cache = OsKeyCache())
                val result: StorageResult<Unit> = if (operation == "ensureKey") quiet.ensureKey() else quiet.put("q", "1")
                assertTrue(wrote.get(), "control: the entry was written during the fetch ($operation)")
                assertIs<StorageError.Unavailable>(failure(result), "$operation over a file that now has entries keeps the conservative answer")
            } finally {
                fake.before = {}
            }
            // The entry survived (read with the cached key, no OS call); empty the file again for the next variant.
            assertEquals(StorageResult.Success("kept"), interactive.get("during-$operation"), "the entry written during the fetch survived ($operation)")
            assertEquals(StorageResult.Success(null), interactive.get("q"))
            assertEquals(StorageResult.Success(Unit), interactive.remove("during-$operation"))
        }

        fake.forcedStatus.clear()
        assertEquals(itemBefore.keys, fake.macItems.keys, "the item was never deleted or replaced")
        itemBefore.forEach { (id: Pair<String, String>, secret: ByteArray) -> assertContentEquals(secret, fake.macItems[id], "item $id") }
        assertEquals(StorageResult.Success(null), store(cache = OsKeyCache()).get("q"), "the original key still opens the store")
        assertEquals(emptyList(), quarantinedAnywhere())
    }

    private companion object {
        val HANG_BOUND = 180.seconds
        const val LATCH_BOUND_SECONDS = 60L
        const val WORKERS = 6
        const val OPS_PER_WORKER = 60
        const val KEYS = 5
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
    }
}
