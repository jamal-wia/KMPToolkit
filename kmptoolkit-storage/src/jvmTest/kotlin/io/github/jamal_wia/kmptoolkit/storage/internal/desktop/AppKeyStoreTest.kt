package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * The app-key store (plan 4.8, T11): every operation verifies the key first, a wrong key is `AccessDenied` and changes
 * nothing, the provider is asked once per operation, `changeKey` rotates the data key atomically. Raw keys everywhere
 * except where PBKDF2 is the subject (each derivation is real work: 600 000 iterations).
 */
class AppKeyStoreTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-appkey").toFile()
    private val name = "app"
    private val dataFile: File = File(directory, "${appKeyStoreId(name)}.bin")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class Provider(private val bytes: ByteArray) {
        val calls: AtomicInteger = AtomicInteger()
        val returned: MutableList<ByteArray> = java.util.Collections.synchronizedList(mutableListOf())
        val key: AppEncryptionKey.Raw = AppEncryptionKey.Raw {
            calls.incrementAndGet()
            bytes.copyOf().also { returned += it }
        }
    }

    private fun raw(seed: Int): Provider = Provider(ByteArray(32) { (it + seed).toByte() })

    private fun store(key: AppEncryptionKey, dir: File = directory, files: FileAccess = createFileAccess(), lock: CrossProcessLock = CrossProcessLock()): JvmAppKeyEncryptedKeyValueStorage =
        JvmAppKeyEncryptedKeyValueStorage(name, dir, key, Dispatchers.IO, lock, files = files)

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result).error

    private fun parsed(file: File = dataFile): ParsedStoreFile = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(file.readBytes())).file

    // ---- the key ----

    @Test
    fun `a raw-key store round-trips, reports APP_PROVIDED_KEY and asks its provider once, then serves from its cache`() = runBlocking {
        val provider: Provider = raw(1)
        val store: JvmAppKeyEncryptedKeyValueStorage = store(provider.key)
        assertEquals(SecureStorageProtection.APP_PROVIDED_KEY, store.protection)

        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
        assertEquals(StorageResult.Success("v"), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.remove("k"))
        assertEquals(StorageResult.Success(null), store.get("k"))

        assertEquals(1, provider.calls.get(), "the first operation created the store with one provider call; the cache served the rest")
        assertEquals(StoreKind.RAW_KEY, parsed().kind)
        assertTrue(provider.returned.all { array -> array.all { it == 0.toByte() } }, "the array the provider returned was wiped")
    }

    @Test
    fun `a second instance with the same key asks its own provider once and an instance with another key is AccessDenied everywhere`() = runBlocking {
        val first: Provider = raw(1)
        val a: JvmAppKeyEncryptedKeyValueStorage = store(first.key)
        assertEquals(StorageResult.Success(Unit), a.put("k", "v"))
        val before: ByteArray = dataFile.readBytes()

        val same: Provider = raw(1)
        assertEquals(StorageResult.Success("v"), store(same.key).get("k"))
        assertEquals(1, same.calls.get())

        val wrong: JvmAppKeyEncryptedKeyValueStorage = store(raw(2).key)
        for (result: StorageResult<*> in listOf(wrong.get("k"), wrong.get("absent"), wrong.put("k", "x"), wrong.remove("k"), wrong.clear(), wrong.ensureKey())) {
            assertIs<StorageError.AccessDenied>(failure(result))
        }
        assertContentEquals(before, dataFile.readBytes(), "a wrong key changes nothing")
        assertEquals(StorageResult.Success("v"), a.get("k"), "instance A is still unlocked")
    }

    @Test
    fun `the provider is asked at most once per operation even when the store changes under the operation`() = runBlocking {
        val provider: Provider = raw(3)
        val store: JvmAppKeyEncryptedKeyValueStorage = store(provider.key)
        assertTrue(store.put("k", "v") is StorageResult.Success)

        val cold: Provider = raw(3)
        val coldStore: JvmAppKeyEncryptedKeyValueStorage = store(cold.key)
        assertEquals(StorageResult.Success("v"), coldStore.get("k"))
        assertEquals(1, cold.calls.get())
        assertEquals(StorageResult.Success("v"), coldStore.get("k"))
        assertEquals(1, cold.calls.get(), "a verified key is cached for the exact header")
    }

    @Test
    fun `a provider that throws is Unavailable with the cause, cancellation propagates, and nothing is created`() = runBlocking {
        val boom = IllegalStateException("vault offline")
        val failing = AppEncryptionKey.Raw { throw boom }
        val answer: StorageError = failure(store(failing).put("k", "v"))
        assertSame(boom, assertIs<StorageError.Unavailable>(answer).cause)
        assertFalse(dataFile.exists())

        val cancelling = AppEncryptionKey.Raw { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { store(cancelling).put("k", "v") }
        assertFalse(dataFile.exists())
    }

    private fun assertSame(expected: Any?, actual: Any?) = assertTrue(expected === actual, "expected the same instance")

    @Test
    fun `a raw key of 31 or 33 bytes, an empty passphrase and a passphrase with a lone surrogate are AccessDenied and create nothing`() = runBlocking {
        for (size: Int in listOf(0, 31, 33)) {
            assertIs<StorageError.AccessDenied>(failure(store(AppEncryptionKey.Raw { ByteArray(size) }).put("k", "v")), "size $size")
        }
        assertIs<StorageError.AccessDenied>(failure(store(AppEncryptionKey.Passphrase({ CharArray(0) })).put("k", "v")))
        assertIs<StorageError.AccessDenied>(failure(store(AppEncryptionKey.Passphrase({ "open\uD800".toCharArray() })).put("k", "v")))
        assertFalse(dataFile.exists())
    }

    // ---- passphrase (PBKDF2) ----

    @Test
    fun `a passphrase store derives its key by PBKDF2-HMAC-SHA256 over the NFC text and opens in NFC and NFD alike`() = runBlocking {
        val composed = "pässwörd é"
        val decomposed: String = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertTrue(composed != decomposed)
        val a: JvmAppKeyEncryptedKeyValueStorage = store(AppEncryptionKey.Passphrase({ composed.toCharArray() }))
        assertEquals(SecureStorageProtection.PASSPHRASE, a.protection)
        assertTrue(a.put("k", "v") is StorageResult.Success)

        // The known-answer path: an independent PBKDF2 over the NFC UTF-8 bytes wraps the same data key.
        val header = parsed().header as HeaderBody.Passphrase
        val expected: ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(composed.toCharArray(), header.salt, header.iterations.toInt(), 256)).encoded
        val dataKey: ByteArray = assertNotNull(
            EnvelopeCipher().unwrapDataKey(expected, header.wrappedKey, SecureFileFormat.wrapAad(StoreKind.PASSPHRASE, header.beforeWrappedKey())),
        )
        assertTrue(EnvelopeCipher().verifyKeyCheck(dataKey, parsed().headerPrefix, parsed().keyCheck))

        val b: JvmAppKeyEncryptedKeyValueStorage = store(AppEncryptionKey.Passphrase({ decomposed.toCharArray() }))
        assertEquals(StorageResult.Success("v"), b.get("k"))
        assertIs<StorageError.AccessDenied>(failure(store(AppEncryptionKey.Passphrase({ "other".toCharArray() })).get("k")))
        Unit
    }

    @Test
    fun `a header asking for 2 to the 32 minus 1 iterations is Undecryptable at once and the provider is never called`() = runBlocking {
        val cipher = EnvelopeCipher()
        val header = HeaderBody.Passphrase(ByteArray(16), 0xFFFF_FFFFL, ByteArray(60))
        val dataKey: ByteArray = cipher.newDataKey()
        val keyCheck: ByteArray = cipher.sealKeyCheck(dataKey, SecureFileFormat.headerPrefix(header))
        dataFile.writeBytes(SecureFileFormat.encode(header, keyCheck, emptyList()))
        val provider: Provider = raw(4)
        val passphraseCalls = AtomicInteger()
        val store: JvmAppKeyEncryptedKeyValueStorage = store(AppEncryptionKey.Passphrase({ passphraseCalls.incrementAndGet(); "x".toCharArray() }))

        assertEquals(StorageError.Undecryptable(null), failure(store.get("k")))
        assertEquals(StorageError.Undecryptable(null), failure(store.ensureKey()))
        assertEquals(0, passphraseCalls.get(), "no derivation and no provider call")
        assertEquals(0, provider.calls.get())
    }

    // ---- changeKey ----

    @Test
    fun `changeKey rotates the data key, the old key is refused afterwards and a copy taken before stays readable with the old key`() = runBlocking {
        val old: Provider = raw(1)
        val a: JvmAppKeyEncryptedKeyValueStorage = store(old.key)
        assertTrue(a.put("a", "1").let { it is StorageResult.Success })
        assertTrue(a.put("b", "2").let { it is StorageResult.Success })
        val copyDirectory: File = File(directory, "copy").apply { mkdirs() }
        dataFile.copyTo(File(copyDirectory, dataFile.name))
        val oldFile: ParsedStoreFile = parsed()
        val oldKek = ByteArray(32) { (it + 1).toByte() }
        val oldDataKey: ByteArray = assertNotNull(
            EnvelopeCipher().unwrapDataKey(oldKek, (oldFile.header as HeaderBody.RawKey).wrappedKey, SecureFileFormat.wrapAad(StoreKind.RAW_KEY, ByteArray(0))),
        )

        val replacement: Provider = raw(9)
        assertEquals(StorageResult.Success(Unit), a.changeKey(replacement.key))

        val newFile: ParsedStoreFile = parsed()
        assertTrue(newFile.entries.all { entry -> EnvelopeCipher().openEntry(oldDataKey, StoreKind.RAW_KEY, entry) == null }, "the old data key fails on every entry of the new file")
        assertEquals(StorageResult.Success("1"), a.get("a"))
        assertEquals(StorageResult.Success("2"), store(raw(9).key).get("b"))
        assertIs<StorageError.AccessDenied>(failure(store(raw(1).key).get("a")))
        assertEquals(StorageResult.Success("1"), store(raw(1).key, dir = copyDirectory).get("a"), "a copy taken before the change stays readable with the old key")
    }

    @Test
    fun `an instance still holding the old key is AccessDenied after another instance changed the key`() = runBlocking {
        val a: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        val b: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        assertTrue(a.put("k", "v") is StorageResult.Success)
        assertEquals(StorageResult.Success("v"), b.get("k"))

        assertEquals(StorageResult.Success(Unit), a.changeKey(raw(2).key))

        assertIs<StorageError.AccessDenied>(failure(b.get("k")))
        assertIs<StorageError.AccessDenied>(failure(b.put("k", "w")))
        assertEquals(StorageResult.Success("v"), a.get("k"))
    }

    @Test
    fun `changeKey with a wrong current key or an unusable new key changes nothing and keeps the old binding`() = runBlocking {
        val a: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        assertTrue(a.put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile.readBytes()

        assertIs<StorageError.AccessDenied>(failure(store(raw(5).key).changeKey(raw(6).key)))
        assertContentEquals(before, dataFile.readBytes())

        val unusable = AppEncryptionKey.Raw { ByteArray(31) }
        assertIs<StorageError.AccessDenied>(failure(a.changeKey(unusable)))
        assertContentEquals(before, dataFile.readBytes())
        assertEquals(StorageResult.Success("v"), a.get("k"), "still bound to the old key")
    }

    @Test
    fun `changeKey is atomic - a write that fails leaves the old file and the old key working`() = runBlocking {
        val real: FileAccess = createFileAccess()
        var failWrites = false
        val flaky = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
                if (failWrites && !path.endsWith(".lock")) FileResult.Failure(FileError.NoSpace(path)) else real.writeAtomically(path, bytes, options)
        }
        val a: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key, files = flaky)
        assertTrue(a.put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile.readBytes()
        failWrites = true

        val error: StorageError = failure(a.changeKey(raw(2).key))

        assertEquals(StorageOperation.CHANGE_KEY, assertIs<StorageError.OperationFailed>(error).operation)
        failWrites = false
        assertContentEquals(before, dataFile.readBytes())
        assertEquals(StorageResult.Success("v"), a.get("k"))
        assertEquals(StorageResult.Success("v"), store(raw(1).key).get("k"))
    }

    @Test
    fun `changeKey from a passphrase to a raw key switches the kind and the reported protection`() = runBlocking {
        val a: JvmAppKeyEncryptedKeyValueStorage = store(AppEncryptionKey.Passphrase({ "first pass".toCharArray() }))
        assertTrue(a.put("k", "v") is StorageResult.Success)
        assertEquals(StorageResult.Success(Unit), a.changeKey(raw(7).key))

        assertEquals(SecureStorageProtection.APP_PROVIDED_KEY, a.protection)
        assertEquals(StoreKind.RAW_KEY, parsed().kind)
        assertEquals(StorageResult.Success("v"), store(raw(7).key).get("k"))
    }

    @Test
    fun `a passphrase store opened with a raw key is AccessDenied on every operation and nothing is moved or changed`() = runBlocking {
        val a: JvmAppKeyEncryptedKeyValueStorage = store(AppEncryptionKey.Passphrase({ "first pass".toCharArray() }))
        assertTrue(a.put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile.readBytes()
        val wrongType: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)

        for (result: StorageResult<*> in listOf(wrongType.get("k"), wrongType.put("k", "w"), wrongType.remove("k"), wrongType.clear(), wrongType.ensureKey(), wrongType.changeKey(raw(2).key))) {
            assertIs<StorageError.AccessDenied>(failure(result))
        }
        assertContentEquals(before, dataFile.readBytes())
        assertTrue(directory.list()!!.none { ".undecryptable-" in it }, "a store of the other key type is never quarantined")
    }

    // ---- clear and the file rows ----

    @Test
    fun `clear with the right key empties the store and keeps the header, an absent store stays absent`() = runBlocking {
        val absent: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        assertEquals(StorageResult.Success(Unit), absent.clear())
        assertFalse(dataFile.exists())

        assertTrue(absent.put("k", "v") is StorageResult.Success)
        val header: ParsedStoreFile = parsed()
        assertEquals(StorageResult.Success(Unit), absent.clear())

        val after: ParsedStoreFile = parsed()
        assertTrue(after.entries.isEmpty())
        assertContentEquals(header.headerPrefix, after.headerPrefix)
        assertEquals(StorageResult.Success(null), absent.get("k"))
    }

    @Test
    fun `an unparsable file is Undecryptable and clear moves it aside, a newer version is never replaced by a put`() = runBlocking {
        val store: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        dataFile.writeBytes(byteArrayOf(5, 5, 5))
        assertEquals(StorageError.Undecryptable(null), failure(store.get("k")))
        assertEquals(StorageError.Undecryptable(null), failure(store.ensureKey()))
        assertEquals(StorageResult.Success(Unit), store.clear())
        assertEquals(1, directory.list()!!.count { ".undecryptable-" in it })

        // An unparsable file is quarantined by put, then a new store is created.
        dataFile.writeBytes(byteArrayOf(6, 6, 6))
        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
        assertEquals(StorageResult.Success("v"), store.get("k"))

        val newer: ByteArray = SecureFileFormat.MAGIC + byteArrayOf(2, 3, 0, 0)
        dataFile.writeBytes(newer)
        assertEquals(StorageError.Undecryptable(null), failure(store.put("k", "w")))
        assertContentEquals(newer, dataFile.readBytes())
    }

    @Test
    fun `a file system that refuses locks fails closed with no provider call and the file untouched`() = runBlocking {
        val provider: Provider = raw(1)
        assertTrue(store(provider.key).put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile.readBytes()
        val refusing = CrossProcessLock(tryLock = { _: FileChannel -> throw IOException("no locks here (test)") })
        val cold: Provider = raw(1)
        val store: JvmAppKeyEncryptedKeyValueStorage = store(cold.key, lock = refusing)

        for (result: StorageResult<*> in listOf(store.get("k"), store.put("k", "w"), store.remove("k"), store.clear(), store.ensureKey(), store.changeKey(raw(2).key))) {
            assertIs<StorageError.OperationFailed>(failure(result))
        }
        assertContentEquals(before, dataFile.readBytes())
        assertEquals(0, cold.calls.get())
    }

    // ---- concurrency and cancellation ----

    @Test
    fun `a provider that waits holds no lock, so another instance writes meanwhile, and cancelling it leaves nothing behind`() = runBlocking {
        val warm: JvmAppKeyEncryptedKeyValueStorage = store(raw(1).key)
        assertTrue(warm.put("seed", "1") is StorageResult.Success)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ByteArray>()
        val slow = AppEncryptionKey.Raw {
            entered.complete(Unit)
            release.await()
        }
        val waiting = async(Dispatchers.Default) { store(slow).get("seed") }
        entered.await()

        assertEquals(StorageResult.Success(Unit), warm.put("during", "2"), "the waiting provider holds neither lock")
        waiting.cancelAndJoin()
        assertEquals(StorageResult.Success("2"), warm.get("during"))
        assertTrue(directory.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `eight instances racing the first use create one header and every value stays readable`() = runBlocking {
        val stores: List<JvmAppKeyEncryptedKeyValueStorage> = List(8) { store(raw(1).key) }
        val results: List<StorageResult<Unit>> =
            (0 until 8).map { index: Int -> async(Dispatchers.Default) { stores[index].put("k$index", "v$index") } }.map { it.await() }

        assertTrue(results.all { it is StorageResult.Success }, results.toString())
        for (index: Int in 0 until 8) assertEquals(StorageResult.Success("v$index"), store(raw(1).key).get("k$index"))
        assertEquals(8, parsed().entries.size)
    }

    @Test
    fun `an unpaired surrogate or an overlong key is InvalidValue before any provider call`() = runBlocking {
        val provider: Provider = raw(1)
        val store: JvmAppKeyEncryptedKeyValueStorage = store(provider.key)
        assertIs<StorageError.InvalidValue>(failure(store.put("k", "\uDC00")))
        assertIs<StorageError.InvalidValue>(failure(store.put("x".repeat(70_000), "v")))
        assertEquals(0, provider.calls.get())
        assertFalse(dataFile.exists())
    }
}
