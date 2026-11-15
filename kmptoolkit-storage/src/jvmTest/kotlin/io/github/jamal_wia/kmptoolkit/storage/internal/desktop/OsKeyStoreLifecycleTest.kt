package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The key lifecycle of plan 4.4 through the OS-key store, over the fake binding (T3, T4, T13). Real time and a real
 * native-executor thread: a call that must really run never uses a virtual-time scheduler (see `NativeExecutorTest`).
 */
class OsKeyStoreLifecycleTest {

    private fun <T> withRig(
        os: DesktopOs = DesktopOs.MACOS,
        block: suspend SecureTestRig.() -> T,
    ): T = SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { rig.block() } }

    private fun SecureTestRig.macKey(name: String = SecureTestRig.NAME, dir: File = directory): Pair<String, String> {
        val identity: StoreIdentity = StoreIdentity.of(name, dir)
        return identity.service to identity.macAccount
    }

    private fun assertFailure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result).error

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    // ---- creation ----

    @Test
    fun `a fresh directory gets one item at the store identity and a sealed file with header and key-check`() = withRig {
        val store: JvmSecureKeyValueStorage = store()

        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))

        assertEquals(setOf(macKey()), fake.macItems.keys)
        assertEquals(32, fake.macItems.values.single().size)
        val parsed = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes()))
        assertEquals(StoreKind.OS_ITEM, parsed.file.kind)
        assertEquals(listOf("k"), parsed.file.entries.map { it.key })
        assertEquals(StorageResult.Success("v"), store.get("k"))
        // Delete (not-found is fine) then add, both inside the locks; the cached key serves the get: no copy call.
        assertEquals(listOf("deleteGenericPassword", "addGenericPassword"), fake.callNames)
    }

    @Test
    fun `ensureKey creates the key once, verifies on an existing store and makes no call for a cached key`() = withRig {
        val store: JvmSecureKeyValueStorage = store()
        assertTrue(store.ensureKey() is StorageResult.Success)
        assertEquals(1, fake.count("addGenericPassword"))
        assertTrue(store.ensureKey() is StorageResult.Success)
        assertEquals(listOf("deleteGenericPassword", "addGenericPassword"), fake.callNames, "a cached key needs no binding call")

        val second: JvmSecureKeyValueStorage = store(cache = OsKeyCache())
        assertTrue(second.ensureKey() is StorageResult.Success)
        assertEquals(1, fake.count("copyGenericPassword"), "an existing store with an empty cache verifies with one fetch")
        assertEquals(1, fake.count("addGenericPassword"))
    }

    @Test
    fun `eight threads on eight instances create exactly one key on first use`() = withRig {
        val cacheShared: OsKeyCache = cache
        val stores: List<JvmSecureKeyValueStorage> = List(8) { store(cache = cacheShared) }
        val results: List<StorageResult<Unit>> = coroutineScope {
            (0 until 8).map { index: Int -> async(Dispatchers.Default) { stores[index].put("k$index", "v$index") } }.awaitAll()
        }

        assertTrue(results.all { it is StorageResult.Success }, "results: $results")
        assertEquals(1, fake.count("addGenericPassword"), "exactly one key was created")
        for (index: Int in 0 until 8) assertEquals(StorageResult.Success("v$index"), stores[0].get("k$index"))
    }

    @Test
    fun `an item present with the file absent is deleted and recreated under the lock`() = withRig {
        val stale = ByteArray(32) { 1 }
        fake.macItems[macKey()] = stale

        assertTrue(store().put("k", "v") is StorageResult.Success)

        assertFalse(fake.macItems.getValue(macKey()).contentEquals(stale), "the stale item was replaced")
        assertEquals(1, fake.count("deleteGenericPassword"))
    }

    // ---- the key is lost ----

    private suspend fun SecureTestRig.storeWithEntries(vararg pairs: Pair<String, String>): JvmSecureKeyValueStorage {
        val store: JvmSecureKeyValueStorage = store()
        for ((key: String, value: String) in pairs) assertTrue(store.put(key, value) is StorageResult.Success)
        return store
    }

    private fun SecureTestRig.loseKey() {
        fake.macItems.clear()
        cache.invalidate(StoreIdentity.of(SecureTestRig.NAME, directory), cache.get(StoreIdentity.of(SecureTestRig.NAME, directory)) ?: ByteArray(0))
    }

    @Test
    fun `with entries and a lost key get of a present entry and ensureKey are Undecryptable, an absent entry is null`() = withRig {
        storeWithEntries("a" to "1", "b" to "2")
        loseKey()
        val before: ByteArray = dataFile().readBytes()
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

        assertEquals(StorageError.Undecryptable(null), assertFailure(store.get("a")))
        assertEquals(StorageResult.Success(null), store.get("never"))
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.ensureKey()))
        assertContentEquals(before, dataFile().readBytes(), "nothing wrote the file")
    }

    @Test
    fun `remove with a lost key drops the entry and copies the header and the other entries byte for byte`() = withRig {
        storeWithEntries("a" to "1", "b" to "2")
        loseKey()
        val original = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

        assertEquals(StorageResult.Success(Unit), store.remove("a"))

        val after = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file
        assertEquals(listOf("b"), after.entries.map { it.key })
        assertContentEquals(original.headerPrefix, after.headerPrefix)
        assertContentEquals(original.keyCheck, after.keyCheck)
        assertEquals(original.entries.single { it.key == "b" }, after.entries.single())
    }

    @Test
    fun `clear with a lost key empties the file, makes no binding call and a later get is null`() = withRig {
        storeWithEntries("a" to "1")
        loseKey()
        fake.callNames.size.let { callsBefore ->
            val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

            assertEquals(StorageResult.Success(Unit), store.clear())
            assertEquals(callsBefore, fake.callNames.size, "clear makes zero binding calls")
            assertEquals(StorageResult.Success(null), store.get("a"))
        }
    }

    @Test
    fun `the next put after a clear with a lost key quarantines nothing and recreates the key`() = withRig {
        storeWithEntries("a" to "1")
        loseKey()
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())
        assertTrue(store.clear() is StorageResult.Success)

        assertTrue(store.put("b", "2") is StorageResult.Success)

        assertTrue(quarantined().isEmpty(), "a zero-entry file is replaced, never quarantined")
        assertEquals(StorageResult.Success("2"), store.get("b"))
        assertEquals(1, fake.macItems.size)
    }

    @Test
    fun `put with a lost key and entries quarantines the file and creates a fresh store`() = withRig {
        storeWithEntries("a" to "1")
        loseKey()
        val before: ByteArray = dataFile().readBytes()
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

        assertTrue(store.put("b", "2") is StorageResult.Success)

        val moved: String = quarantined().single()
        assertContentEquals(before, File(directory, moved).readBytes(), "the quarantined file keeps every byte")
        assertEquals(StorageResult.Success(null), store.get("a"))
        assertEquals(StorageResult.Success("2"), store.get("b"))
        assertTrue(moved.startsWith(dataFile().name + ".undecryptable-20261115T120000Z"), moved)
    }

    @Test
    fun `two quarantines in one second keep both files`() = withRig {
        val store: JvmSecureKeyValueStorage = store()
        dataFile().writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.put("a", "1") is StorageResult.Success)
        // Make the new file unparsable again within the same fixed second.
        val firstNew: ByteArray = dataFile().readBytes()
        dataFile().writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(store.put("a", "2") is StorageResult.Success)

        val moved: List<String> = quarantined()
        assertEquals(2, moved.size, "no-replace move with a counter: $moved")
        assertTrue(moved.any { it.endsWith("Z") } && moved.any { it.endsWith("Z-1") }, moved.toString())
        assertTrue(firstNew.isNotEmpty())
    }

    @Test
    fun `an item replaced by another key fails the key-check and reads as Undecryptable, never as a value`() = withRig {
        storeWithEntries("a" to "1")
        fake.macItems[macKey()] = ByteArray(32) { 7 }
        cache.invalidate(StoreIdentity.of(SecureTestRig.NAME, directory), cache.get(StoreIdentity.of(SecureTestRig.NAME, directory))!!)

        assertEquals(StorageError.Undecryptable(null), assertFailure(store(cache = OsKeyCache()).get("a")))
    }

    @Test
    fun `a header that names another identity is a lost key before any OS call`() = withRig {
        storeWithEntries("a" to "1")
        // The same file moved into another directory: the header fingerprint is the old directory's.
        val other: File = File(directory, "moved").apply { mkdirs() }
        val moved: File = File(other, dataFile().name)
        dataFile().copyTo(moved)
        val callsBefore: Int = fake.callNames.size

        val store: JvmSecureKeyValueStorage = store(dir = other, cache = OsKeyCache())
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.get("a")))
        assertEquals(callsBefore, fake.callNames.size, "no lookup of any account was made")

        assertTrue(store.put("b", "2") is StorageResult.Success)
        assertEquals(StorageResult.Success("2"), store.get("b"))
        assertEquals(2, fake.macItems.size, "the new directory has its own item; the old one is untouched")
        assertTrue(fake.macItems.containsKey(macKey()))
    }

    @Test
    fun `two stores with one name in two directories have two identities`() = withRig {
        val dirB: File = File(directory, "b").apply { mkdirs() }
        assertTrue(store().put("k", "A") is StorageResult.Success)
        assertTrue(store(dir = dirB).put("k", "B") is StorageResult.Success)

        assertEquals(2, fake.macItems.size)
        assertEquals(StorageResult.Success("A"), store(cache = OsKeyCache()).get("k"))
        assertEquals(StorageResult.Success("B"), store(dir = dirB, cache = OsKeyCache()).get("k"))
    }

    // ---- never lost: locked, denied, hung, missing ----

    @Test
    fun `without interaction a not-found is never a lost key under the conservative rule and nothing is touched`() = withRig {
        storeWithEntries("a" to "1")
        fake.macItems.clear()
        val before: ByteArray = dataFile().readBytes()
        val store: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cache = OsKeyCache())

        assertIs<StorageError.Unavailable>(assertFailure(store.get("a")))
        assertIs<StorageError.Unavailable>(assertFailure(store.put("b", "2")))
        assertEquals(1, fake.count("addGenericPassword"), "no key was created after the first one")
        assertContentEquals(before, dataFile().readBytes())
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun `an interaction-not-allowed status is Unavailable and never leads to quarantine or creation`() = withRig {
        storeWithEntries("a" to "1")
        cache.invalidate(StoreIdentity.of(SecureTestRig.NAME, directory), cache.get(StoreIdentity.of(SecureTestRig.NAME, directory))!!)
        fake.forcedStatus["copyGenericPassword"] = -25308
        val before: ByteArray = dataFile().readBytes()
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

        assertIs<StorageError.Unavailable>(assertFailure(store.get("a")))
        assertIs<StorageError.Unavailable>(assertFailure(store.put("b", "2")))
        assertContentEquals(before, dataFile().readBytes())
        assertTrue(quarantined().isEmpty())
        assertEquals(StorageResult.Success(Unit), store.clear(), "clear needs no key")
    }

    @Test
    fun `a refused delete of a planted item answers AccessDenied and leaves the store byte-identical`() = withRig {
        storeWithEntries("a" to "1")
        fake.macItems[macKey()] = ByteArray(32) { 5 } // planted: fails the key-check
        cache.invalidate(StoreIdentity.of(SecureTestRig.NAME, directory), cache.get(StoreIdentity.of(SecureTestRig.NAME, directory))!!)
        fake.forcedStatus["deleteGenericPassword"] = -25293
        val before: ByteArray = dataFile().readBytes()
        val store: JvmSecureKeyValueStorage = store(cache = OsKeyCache())

        val error: StorageError = assertFailure(store.put("b", "2"))

        assertEquals(StorageError.AccessDenied(platformCode = -25293), error)
        assertContentEquals(before, dataFile().readBytes(), "the quarantine was renamed back")
        assertTrue(quarantined().isEmpty())
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.get("a")))
    }

    @Test
    fun `a failed add rolls the created file back and the answer is the mapped error`() = withRig {
        fake.forcedStatus["addGenericPassword"] = -25308
        val store: JvmSecureKeyValueStorage = store()

        assertIs<StorageError.Unavailable>(assertFailure(store.put("k", "v")))

        assertFalse(dataFile().exists(), "the created file was deleted")
        assertTrue(fileNames().none { it.endsWith(".tmp") })
    }

    @Test
    fun `without interaction every macOS call carries allowUi false`() = withRig {
        val store: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false))
        assertTrue(store.put("k", "v") is StorageResult.Success)

        assertTrue(fake.calls.all { it.detail.endsWith("ui=false") }, fake.calls.toString())
    }

    // ---- clear ----

    @Test
    fun `clear makes no binding call, keeps the header and the lock file and removes entries and leftover temps`() = withRig {
        val store: JvmSecureKeyValueStorage = storeWithEntries("a" to "1")
        val header = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file
        val temp = File(directory, "${dataFile().name}7.tmp").apply { writeText("x") }
        val neighbour = File(directory, "${dataFile().name}.other").apply { writeText("keep") }
        val calls: Int = fake.callNames.size

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(calls, fake.callNames.size)
        val after = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file
        assertTrue(after.entries.isEmpty())
        assertContentEquals(header.headerPrefix, after.headerPrefix)
        assertContentEquals(header.keyCheck, after.keyCheck)
        assertFalse(temp.exists())
        assertTrue(neighbour.exists())
        assertTrue(File(directory, dataFile().name + ".lock").isFile, "the lock file is kept")
        assertEquals(StorageResult.Success(null), store.get("a"))
    }

    @Test
    fun `reading, removing or clearing a store whose directory does not exist creates nothing`() = withRig {
        val missing: File = File(directory, "not-yet")
        val store: JvmSecureKeyValueStorage = store(dir = missing)

        assertEquals(StorageResult.Success(null), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.remove("k"))
        assertEquals(StorageResult.Success(Unit), store.clear())

        assertFalse(missing.exists())
        assertEquals(0, fake.callNames.size)
    }

    // ---- canary (T3) ----

    @Test
    fun `plaintext never reaches the disk and only 32 bytes ever reach the OS`() = withRig {
        val canary = "CANARY-6f1d6b38-5c0e-4a52-9a2b-0f6a1d5e4b77"
        val store: JvmSecureKeyValueStorage = store()
        assertTrue(store.put("name", canary) is StorageResult.Success)
        assertTrue(store.put("other", canary + canary) is StorageResult.Success)
        assertTrue(store.remove("other") is StorageResult.Success)
        assertTrue(store.put("again", canary) is StorageResult.Success)
        assertEquals(2, assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file.entries.size, "control: values are stored")

        for ((name: String, bytes: ByteArray) in allBytes()) {
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(canary), "the canary value is in $name")
        }
        assertTrue(fake.calls.filter { it.name == "addGenericPassword" }.all { "32 bytes" in it.detail })
    }

    @Test
    fun `a failing backend never lets a put create or change a file`() = withRig {
        val failing: List<(FakeNativeKeyStoreBinding) -> Unit> = listOf(
            { it.forcedStatus["deleteGenericPassword"] = -25308 },
            { it.forcedStatus["addGenericPassword"] = -25293 },
            { it.forcedStatus["addGenericPassword"] = -25307 },
        )
        for (arrange: (FakeNativeKeyStoreBinding) -> Unit in failing) {
            fake.forcedStatus.clear()
            arrange(fake)
            assertFalse(store().put("k", "CANARY-x") is StorageResult.Success)
            assertFalse(dataFile().exists(), "a data file exists after a failed first put")
            assertTrue(fileNames().none { it.endsWith(".tmp") || ".undecryptable-" in it })
        }
    }

    @Test
    fun `an unusable binding answers Unsupported for every call and touches nothing`() = withRig {
        val selection = BindingSelection.Unsupported(StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_RUNTIME))
        val store: JvmSecureKeyValueStorage = store(source = FixedBindingSource(selection))

        for (result: StorageResult<*> in listOf(store.get("k"), store.put("k", "v"), store.remove("k"), store.clear(), store.ensureKey())) {
            assertEquals(StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_RUNTIME), assertFailure(result))
        }
        assertEquals(emptyList(), fileNames(), "not even a lock file")
    }

    @Test
    fun `an empty key and an empty value are stored`() = withRig {
        val store: JvmSecureKeyValueStorage = store()
        assertTrue(store.put("", "") is StorageResult.Success)
        assertEquals(StorageResult.Success(""), store.get(""))
        assertEquals(StorageResult.Success(null), store.get("x"))
    }

    @Test
    fun `an unpaired surrogate is InvalidValue before any lock or call`() = withRig {
        val store: JvmSecureKeyValueStorage = store()
        assertEquals("k", assertIs<StorageError.InvalidValue>(assertFailure(store.put("k", "a\uD800"))).key)
        assertIs<StorageError.InvalidValue>(assertFailure(store.put("\uDC00", "v")))
        assertEquals(emptyList(), fileNames())
        assertEquals(0, fake.callNames.size)
    }

    @Test
    fun `a directory that is a file answers OperationFailed and an unreadable value is never invented`() = withRig {
        val notADirectory: File = File(directory, "plain").apply { writeText("x") }
        val store: JvmSecureKeyValueStorage = store(dir = notADirectory)

        assertIs<StorageError.OperationFailed>(assertFailure(store.put("k", "v")))
        assertEquals(0, fake.count("addGenericPassword"))
    }

    @Test
    fun `an unparsable file answers Undecryptable on get, remove and ensureKey and clear quarantines it`() = withRig {
        dataFile().writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        val store: JvmSecureKeyValueStorage = store()
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.get("k")))
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.remove("k")))
        assertEquals(StorageError.Undecryptable(null), assertFailure(store.ensureKey()))
        assertEquals(0, fake.callNames.size, "before any OS call")

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertFalse(dataFile().exists())
        assertEquals(1, quarantined().size)
        assertEquals(0, fake.callNames.size, "clear creates nothing and calls nothing")
        assertNull(store.get("k").let { (it as StorageResult.Success).value })
    }

    @Test
    fun `a file of a newer format version is never replaced by a put, only clear moves it`() = withRig {
        val newer: ByteArray = SecureFileFormat.MAGIC + byteArrayOf(2, 1, 0, 0)
        dataFile().writeBytes(newer)
        val store: JvmSecureKeyValueStorage = store()

        assertEquals(StorageError.Undecryptable(null), assertFailure(store.put("k", "v")))
        assertContentEquals(newer, dataFile().readBytes())
        assertTrue(store.clear() is StorageResult.Success)
        assertEquals(1, quarantined().size)
    }
}
