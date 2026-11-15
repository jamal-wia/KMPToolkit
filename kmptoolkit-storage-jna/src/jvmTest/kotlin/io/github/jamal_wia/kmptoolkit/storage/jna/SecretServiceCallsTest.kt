package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A `GError` a fake call reports: its domain, code and the D-Bus error name of a remote error. */
internal class FakeGError(val domain: String, val code: Int, val remoteName: String? = null)

/** A fake `SecretRetrievable`: a `SecretItem` (with its lock) or another retrievable, and what retrieving its secret does. */
internal class FakeRetrievable(
    val isItem: Boolean = true,
    val locked: Boolean = false,
    val secret: ByteArray? = null,
    val retrieveError: FakeGError? = null,
    val retrieveReturnsNull: Boolean = false,
)

/** What one fake variadic call received. */
internal class SecretCall(val kind: String, val flags: Int, val schemaName: String, val schemaAttributes: List<String>, val attributes: Map<String, String>, val extra: Map<String, Any?>)

/**
 * A scriptable libsecret, glib, gobject and gio over real native memory (`GList` nodes, `GError` structs, `SecretValue`
 * blocks). Every reference-counted object, `GError`, secret value, remote-name string and list the calls hand out must be
 * given back: [leaks] names what was not.
 */
internal class FakeLinuxLibraries {
    private class GObj(val memory: Memory, val retrievable: FakeRetrievable?, val typeName: String)

    private val objects: ConcurrentHashMap<Long, GObj> = ConcurrentHashMap()
    private val errors: ConcurrentHashMap<Long, Pair<Memory, FakeGError>> = ConcurrentHashMap()
    private val values: ConcurrentHashMap<Long, Memory> = ConcurrentHashMap()
    private val strings: ConcurrentHashMap<Long, Memory> = ConcurrentHashMap()
    private val lists: ConcurrentHashMap<Long, List<Memory>> = ConcurrentHashMap()
    private val quarks: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
    private val quarkStrings: ConcurrentHashMap<Int, Memory> = ConcurrentHashMap()
    private val typeStrings: ConcurrentHashMap<Long, Memory> = ConcurrentHashMap()

    val calls: CopyOnWriteArrayList<SecretCall> = CopyOnWriteArrayList()
    val created: AtomicInteger = AtomicInteger()
    val passwordsSeen: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

    @Volatile var searchError: FakeGError? = null
    @Volatile var searchResults: List<FakeRetrievable> = emptyList()
    @Volatile var storeError: FakeGError? = null
    @Volatile var clearError: FakeGError? = null
    @Volatile var serviceError: FakeGError? = null
    @Volatile var collectionError: FakeGError? = null
    @Volatile var collectionExists: Boolean = true
    @Volatile var collectionLocked: Boolean = false
    @Volatile var backendTypeName: String = "SecretService"
    @Volatile var backendError: FakeGError? = null
    @Volatile var backendMissing: Boolean = false

    /** Everything a call left behind: objects, errors, values, strings and lists that were never given back. */
    val leaks: String
        get() = listOf("objects" to objects.size, "errors" to errors.size, "values" to values.size, "strings" to strings.size, "lists" to lists.size)
            .filter { it.second != 0 }.joinToString { "${it.first}=${it.second}" }

    private fun newObject(retrievable: FakeRetrievable?, typeName: String): Pointer {
        val memory = Memory(16)
        val klass = Memory(8)
        klass.setLong(0L, typeNames.indexOf(typeName).toLong() + 100L)
        memory.setPointer(0L, klass)
        objects[Pointer.nativeValue(memory)] = GObj(memory, retrievable, typeName)
        created.incrementAndGet()
        return memory
    }

    private fun newError(spec: FakeGError?, slot: PointerByReference) {
        if (spec == null) return
        val memory = Memory(16)
        val quark: Int = synchronized(quarks) { quarks.indexOf(spec.domain).takeIf { it >= 0 } ?: run { quarks.add(spec.domain); quarks.size - 1 } } + 1
        memory.setInt(0L, quark)
        memory.setInt(4L, spec.code)
        errors[Pointer.nativeValue(memory)] = memory to spec
        created.incrementAndGet()
        slot.value = memory
    }

    private fun readSchema(schema: Pointer): Pair<String, List<String>> {
        val name: String = cString(schema.getPointer(0L))!!
        val attributeNames: ArrayList<String> = ArrayList()
        var offset: Long = 2L * Native.POINTER_SIZE
        while (true) {
            val attribute: String = cString(schema.getPointer(offset)) ?: break
            assertEquals(0, schema.getInt(offset + Native.POINTER_SIZE), "every attribute of the schema is a string")
            attributeNames.add(attribute)
            offset += 2L * Native.POINTER_SIZE
        }
        return name to attributeNames
    }

    private fun readPairs(tail: Array<out Pointer?>): Map<String, String> {
        val result: LinkedHashMap<String, String> = LinkedHashMap()
        assertNull(tail.last(), "a variadic attribute list ends with a NULL pointer")
        var index = 0
        while (tail[index] != null) {
            result[cString(tail[index])!!] = cString(tail[index + 1])!!
            index += 2
        }
        assertEquals(tail.size - 1, index, "no stray argument after the pairs")
        return result
    }

    val glib: GLibApi = object : GLibApi {
        override fun g_error_free(error: Pointer) {
            errors.remove(Pointer.nativeValue(error)) ?: error("double free or unknown GError")
        }

        override fun g_quark_to_string(quark: Int): Pointer? {
            val text: String = quarks[quark - 1]
            return quarkStrings.getOrPut(quark) { Memory(text.length + 1L).also { it.setString(0L, text, "UTF-8") } }
        }

        override fun g_free(memory: Pointer?) {
            strings.remove(Pointer.nativeValue(memory)) ?: error("g_free of memory that is not an allocated string")
        }

        override fun g_list_free(list: Pointer?) {
            lists.remove(Pointer.nativeValue(list)) ?: error("g_list_free of an unknown or already freed list")
        }
    }

    val gobject: GObjectApi = object : GObjectApi {
        override fun g_object_unref(instance: Pointer) {
            objects.remove(Pointer.nativeValue(instance)) ?: error("unref of an unknown or already released object")
        }

        override fun g_type_check_instance_is_a(instance: Pointer, type: Long): Boolean =
            type == ITEM_TYPE && (objects[Pointer.nativeValue(instance)] ?: error("type check of a released object")).retrievable?.isItem == true

        override fun g_type_name(type: Long): Pointer? =
            typeStrings.getOrPut(type) { Memory(64).also { it.setString(0L, typeNames[(type - 100L).toInt()], "UTF-8") } }
    }

    val gio: GioApi = object : GioApi {
        override fun g_dbus_error_get_remote_error(error: Pointer): Pointer? {
            val name: String = errors[Pointer.nativeValue(error)]?.second?.remoteName ?: return null
            val memory = Memory(name.length + 1L)
            memory.setString(0L, name, "UTF-8")
            strings[Pointer.nativeValue(memory)] = memory
            return memory
        }
    }

    val secret: SecretApi = object : SecretApi {
        override fun secret_password_search_sync(schema: Pointer, flags: Int, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Pointer? {
            val (name: String, names: List<String>) = readSchema(schema)
            calls.add(SecretCall("search", flags, name, names, readPairs(attributes), emptyMap()))
            searchError?.let { newError(it, error); return null }
            val results: List<FakeRetrievable> = searchResults
            if (results.isEmpty()) return null
            val nodes: List<Memory> = results.map { Memory(2L * Native.POINTER_SIZE) }
            for ((index: Int, retrievable: FakeRetrievable) in results.withIndex()) {
                nodes[index].setPointer(0L, newObject(retrievable, if (retrievable.isItem) "SecretItem" else "SecretFileItem"))
                nodes[index].setPointer(Native.POINTER_SIZE.toLong(), nodes.getOrNull(index + 1))
            }
            lists[Pointer.nativeValue(nodes[0])] = nodes
            created.incrementAndGet()
            return nodes[0]
        }

        override fun secret_password_store_sync(schema: Pointer, collection: Pointer, label: Pointer, password: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Boolean {
            val (name: String, names: List<String>) = readSchema(schema)
            var length = 0
            while (password.getByte(length.toLong()) != 0.toByte()) length++
            passwordsSeen.add(password.getByteArray(0L, length))
            calls.add(SecretCall("store", 0, name, names, readPairs(attributes), mapOf("collection" to cString(collection), "label" to cString(label))))
            storeError?.let { newError(it, error); return false }
            return true
        }

        override fun secret_password_clear_sync(schema: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Boolean {
            val (name: String, names: List<String>) = readSchema(schema)
            calls.add(SecretCall("clear", 0, name, names, readPairs(attributes), emptyMap()))
            clearError?.let { newError(it, error); return false }
            return false
        }

        override fun secret_retrievable_retrieve_secret_sync(retrievable: Pointer, cancellable: Pointer?, error: PointerByReference): Pointer? {
            val spec: FakeRetrievable = objects[Pointer.nativeValue(retrievable)]!!.retrievable!!
            spec.retrieveError?.let { newError(it, error); return null }
            if (spec.retrieveReturnsNull) return null
            val bytes: ByteArray = spec.secret!!
            val memory = Memory(maxOf(1L, bytes.size.toLong()))
            memory.write(0L, bytes, 0, bytes.size)
            values[Pointer.nativeValue(memory)] = memory
            created.incrementAndGet()
            return memory
        }

        override fun secret_value_get(value: Pointer, length: Pointer): Pointer? {
            val memory: Memory = values[Pointer.nativeValue(value)] ?: error("secret_value_get of a released value")
            val size: Long = memory.size()
            length.setNativeLong(0L, com.sun.jna.NativeLong(secretLengths[Pointer.nativeValue(value)] ?: size))
            return memory
        }

        override fun secret_value_unref(value: Pointer) {
            values.remove(Pointer.nativeValue(value)) ?: error("secret_value_unref of an unknown or already released value")
        }

        override fun secret_item_get_type(): Long = ITEM_TYPE

        override fun secret_item_get_locked(item: Pointer): Boolean = (objects[Pointer.nativeValue(item)] ?: error("lock query of a released object")).retrievable!!.locked

        override fun secret_service_get_sync(flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer? {
            serviceError?.let { newError(it, error); return null }
            return newObject(null, "SecretService")
        }

        override fun secret_collection_for_alias_sync(service: Pointer, alias: Pointer, flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer? {
            assertEquals("default", cString(alias), "the default collection")
            assertTrue(objects.containsKey(Pointer.nativeValue(service)), "the service is alive while its collection is read")
            collectionError?.let { newError(it, error); return null }
            return if (collectionExists) newObject(null, "SecretCollection") else null
        }

        override fun secret_collection_get_locked(collection: Pointer): Boolean = collectionLocked

        override fun secret_backend_get_sync(flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer? {
            if (backendMissing) throw UnsatisfiedLinkError("secret_backend_get_sync")
            backendError?.let { newError(it, error); return null }
            return newObject(null, backendTypeName)
        }
    }

    private val secretLengths: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    val platform: LinuxPlatform = LinuxPlatform.Ready(LinuxLibraries(glib, gobject, gio, secret))

    companion object {
        const val ITEM_TYPE: Long = 77L
        val typeNames: List<String> = listOf("SecretItem", "SecretFileItem", "SecretService", "SecretCollection", "SecretFileBackend")
    }
}

/** The Linux call object over the scripted libraries: the variadic calls, the lookup verdicts, and what it frees. */
class SecretServiceCallsTest {

    private val attributes: Map<String, String> = linkedMapOf("service" to "svc ünï.kmptoolkit.securestorage", "directory" to "0123456789abcdef0123456789abcdef")
    private val secret: ByteArray = "c2VjcmV0LWJhc2U2NA==".toByteArray()

    private fun calls(fake: FakeLinuxLibraries, scratch: RecordingScratch = RecordingScratch()): JnaSecretServiceCalls =
        JnaSecretServiceCalls(lazyOf(fake.platform), scratch)

    @Test
    fun `a lookup of one unlocked item returns its secret and gives every reference back`() {
        val fake = FakeLinuxLibraries()
        fake.searchResults = listOf(FakeRetrievable(secret = secret))
        val scratch = RecordingScratch()
        val result: RawResult<SecretLookup> = calls(fake, scratch).lookup(attributes, allowUi = true)

        val found: SecretLookup.Found = assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(result).value)
        assertContentEquals(secret, found.secret)
        val call: SecretCall = fake.calls.single()
        assertEquals(6, call.flags, "SECRET_SEARCH_ALL | SECRET_SEARCH_UNLOCK when a dialog is allowed")
        assertEquals(JnaSecretServiceCalls.SCHEMA_NAME, call.schemaName)
        assertEquals(attributes.keys.toList(), call.schemaAttributes)
        assertEquals(attributes, call.attributes, "both attributes reach the call, UTF-8 intact")
        assertEquals("", fake.leaks, "the list, the object and the secret value are all given back")
        assertTrue(fake.created.get() >= 3, "control: a list, an object and a value existed")
        scratch.assertAllFreedAndWiped("lookup")
    }

    @Test
    fun `a non-interactive lookup does not ask to unlock`() {
        val fake = FakeLinuxLibraries()
        calls(fake).lookup(attributes, allowUi = false)
        assertEquals(2, fake.calls.single().flags, "SECRET_SEARCH_ALL only")
    }

    @Test
    fun `no match is not-found, and a locked match is never not-found`() {
        val none = FakeLinuxLibraries()
        assertSame(SecretLookup.NotFound, assertIs<RawResult.Success<SecretLookup>>(calls(none).lookup(attributes, true)).value)
        assertEquals("", none.leaks)

        val locked = FakeLinuxLibraries()
        locked.searchResults = listOf(FakeRetrievable(locked = true))
        assertSame(SecretLookup.LockedMatch, assertIs<RawResult.Success<SecretLookup>>(calls(locked).lookup(attributes, false)).value)
        assertEquals("", locked.leaks, "a locked match still releases the list and the object")
        assertEquals(0, locked.passwordsSeen.size)
    }

    @Test
    fun `an unlocked match wins over a locked one, and a non-item retrievable is never treated as locked`() {
        val mixed = FakeLinuxLibraries()
        mixed.searchResults = listOf(FakeRetrievable(locked = true), FakeRetrievable(secret = secret), FakeRetrievable(isItem = false, locked = true, secret = secret))
        val found = assertIs<RawResult.Success<SecretLookup>>(calls(mixed).lookup(attributes, true)).value
        assertContentEquals(secret, assertIs<SecretLookup.Found>(found).secret)
        assertEquals("", mixed.leaks, "every element of the list is released, not only the one that matched")

        val file = FakeLinuxLibraries()
        file.searchResults = listOf(FakeRetrievable(isItem = false, locked = true, secret = secret))
        assertIs<SecretLookup.Found>(assertIs<RawResult.Success<SecretLookup>>(calls(file).lookup(attributes, true)).value)
    }

    @Test
    fun `a search error carries the domain, code and D-Bus name and frees the error and the name`() {
        val fake = FakeLinuxLibraries()
        fake.searchError = FakeGError("g-dbus-error-quark", 2, "org.freedesktop.DBus.Error.ServiceUnknown")
        val result = calls(fake).lookup(attributes, true)
        val status: RawStatus = assertIs<RawResult.Failure>(result).status
        assertEquals(RawStatus(2, "g-dbus-error-quark", "org.freedesktop.DBus.Error.ServiceUnknown"), status)
        assertEquals("", fake.leaks, "the GError and the remote-name string are freed")
        assertTrue(fake.created.get() >= 1, "control: an error existed")
    }

    @Test
    fun `a local error has no D-Bus name`() {
        val fake = FakeLinuxLibraries()
        fake.searchError = FakeGError("g-io-error-quark", 24)
        val status: RawStatus = assertIs<RawResult.Failure>(calls(fake).lookup(attributes, true)).status
        assertEquals(RawStatus(24, "g-io-error-quark", null), status)
        assertEquals("", fake.leaks)
    }

    @Test
    fun `a retrieve error and a null secret are failures, and the list is still released`() {
        val failing = FakeLinuxLibraries()
        failing.searchResults = listOf(FakeRetrievable(retrieveError = FakeGError("secret-error-quark", 2)))
        assertEquals(RawStatus(2, "secret-error-quark"), assertIs<RawResult.Failure>(calls(failing).lookup(attributes, true)).status)
        assertEquals("", failing.leaks)

        val empty = FakeLinuxLibraries()
        empty.searchResults = listOf(FakeRetrievable(retrieveReturnsNull = true))
        val status: RawStatus = assertIs<RawResult.Failure>(calls(empty).lookup(attributes, true)).status
        assertFalse(status.isSuccess)
        assertEquals("", empty.leaks)
    }

    @Test
    fun `a store passes the label, the default collection, the attributes and the secret, and wipes its copy`() {
        val fake = FakeLinuxLibraries()
        val scratch = RecordingScratch()
        val status: RawStatus = calls(fake, scratch).store("My Label", attributes, secret)

        assertEquals(RawStatus.SUCCESS, status)
        val call: SecretCall = fake.calls.single()
        assertEquals("store", call.kind)
        assertEquals("default", call.extra["collection"])
        assertEquals("My Label", call.extra["label"])
        assertEquals(attributes, call.attributes)
        assertContentEquals(secret, fake.passwordsSeen.single(), "the secret arrived NUL-terminated and intact")
        assertEquals("", fake.leaks)
        scratch.assertAllFreedAndWiped("store")
    }

    @Test
    fun `a failed store reports the error and still wipes the secret`() {
        val fake = FakeLinuxLibraries()
        fake.storeError = FakeGError("g-io-error-quark", 1, "org.freedesktop.Secret.Error.IsLocked")
        val scratch = RecordingScratch()
        val status: RawStatus = calls(fake, scratch).store("l", attributes, secret)
        assertEquals(RawStatus(1, "g-io-error-quark", "org.freedesktop.Secret.Error.IsLocked"), status)
        assertEquals("", fake.leaks)
        scratch.assertAllFreedAndWiped("failed store")
    }

    @Test
    fun `clearing with nothing to remove is a success, an error is a failure`() {
        val fake = FakeLinuxLibraries()
        assertEquals(RawStatus.SUCCESS, calls(fake).clear(attributes), "the boolean 'removed something' is not an error")
        assertEquals(attributes, fake.calls.single().attributes)
        fake.clearError = FakeGError("g-io-error-quark", 5)
        assertEquals(RawStatus(5, "g-io-error-quark"), calls(fake).clear(attributes))
        assertEquals("", fake.leaks)
    }

    @Test
    fun `the default collection lock is read and the service and collection are released`() {
        val fake = FakeLinuxLibraries()
        fake.collectionLocked = true
        assertEquals(true, assertIs<RawResult.Success<Boolean>>(calls(fake).defaultCollectionLocked()).value)
        fake.collectionLocked = false
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(calls(fake).defaultCollectionLocked()).value)
        assertEquals("", fake.leaks)
        assertEquals(4, fake.created.get(), "control: a service and a collection per call")
    }

    @Test
    fun `an absent default collection counts as locked, and a service error is a failure with nothing leaked`() {
        val absent = FakeLinuxLibraries()
        absent.collectionExists = false
        assertEquals(true, assertIs<RawResult.Success<Boolean>>(calls(absent).defaultCollectionLocked()).value)
        assertEquals("", absent.leaks, "the service is released even though there is no collection")

        val noService = FakeLinuxLibraries()
        noService.serviceError = FakeGError("g-io-error-quark", 1, "org.freedesktop.DBus.Error.ServiceUnknown")
        assertIs<RawResult.Failure>(calls(noService).defaultCollectionLocked())
        assertEquals("", noService.leaks)

        val badCollection = FakeLinuxLibraries()
        badCollection.collectionError = FakeGError("secret-error-quark", 3)
        assertIs<RawResult.Failure>(calls(badCollection).defaultCollectionLocked())
        assertEquals("", badCollection.leaks, "the service is released when the collection lookup fails")
    }

    @Test
    fun `the portal is recognised by libsecret's file backend, nothing else`() {
        val portal = FakeLinuxLibraries()
        portal.backendTypeName = "SecretFileBackend"
        assertEquals(true, assertIs<RawResult.Success<Boolean>>(calls(portal).backendIsPortal()).value)
        assertEquals("", portal.leaks)
        val service = FakeLinuxLibraries()
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(calls(service).backendIsPortal()).value)
        assertEquals("", service.leaks)
    }

    @Test
    fun `a libsecret without the optional backend symbol says not portal instead of failing`() {
        val fake = FakeLinuxLibraries()
        fake.backendMissing = true
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(calls(fake).backendIsPortal()).value)
        fake.backendMissing = false
        fake.backendError = FakeGError("g-io-error-quark", 1)
        assertIs<RawResult.Failure>(calls(fake).backendIsPortal())
        assertEquals("", fake.leaks)
    }

    @Test
    fun `a machine without libsecret 0_19 answers every call with the missing-symbol status, never an exception`() {
        val missing: JnaSecretServiceCalls = JnaSecretServiceCalls(lazyOf(LinuxPlatform.SymbolMissing))
        assertTrue(assertIs<RawResult.Failure>(missing.lookup(attributes, true)).status.isSymbolMissing)
        assertTrue(missing.store("l", attributes, secret).isSymbolMissing)
        assertTrue(missing.clear(attributes).isSymbolMissing)
        assertTrue(assertIs<RawResult.Failure>(missing.defaultCollectionLocked()).status.isSymbolMissing)
        assertTrue(assertIs<RawResult.Failure>(missing.backendIsPortal()).status.isSymbolMissing)
    }

    @Test
    fun `a schema of more than 32 attributes is refused before any native call`() {
        val fake = FakeLinuxLibraries()
        val tooMany: Map<String, String> = (0..32).associate { "a$it" to "v" }
        assertFailsWith<IllegalArgumentException> { calls(fake).clear(tooMany) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a link error from libsecret leaves nothing behind`() {
        val fake = FakeLinuxLibraries()
        val broken: FakeLinuxLibraries = fake
        val platform = LinuxPlatform.Ready(
            LinuxLibraries(
                broken.glib, broken.gobject, broken.gio,
                object : SecretApi by broken.secret {
                    override fun secret_password_store_sync(schema: Pointer, collection: Pointer, label: Pointer, password: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Boolean =
                        throw NoSuchMethodError("secret_password_store_sync")
                },
            ),
        )
        val scratch = RecordingScratch()
        assertFailsWith<NoSuchMethodError> { JnaSecretServiceCalls(lazyOf(platform), scratch).store("l", attributes, secret) }
        scratch.assertAllFreedAndWiped("throwing store")
    }

    @Test
    fun `many threads and a thousand operations leave nothing alive (seed 5103)`() {
        val seed = 5103
        val fake = FakeLinuxLibraries()
        fake.searchResults = listOf(FakeRetrievable(locked = true), FakeRetrievable(secret = secret))
        val scratch = RecordingScratch()
        val calls: JnaSecretServiceCalls = calls(fake, scratch)
        val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val start = CountDownLatch(1)
        val threads: List<Thread> = (0 until 8).map { worker: Int ->
            Thread {
                val random = Random(seed * 1000 + worker)
                start.await()
                repeat(125) {
                    try {
                        when (random.nextInt(5)) {
                            0 -> calls.lookup(attributes, random.nextBoolean())
                            1 -> calls.store("l", attributes, secret)
                            2 -> calls.clear(attributes)
                            3 -> calls.defaultCollectionLocked()
                            else -> calls.backendIsPortal()
                        }
                    } catch (error: Throwable) {
                        problems.add("seed=$seed worker=$worker: $error")
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        for (thread: Thread in threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60))
            assertFalse(thread.isAlive, "seed=$seed: a worker hung")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertTrue(fake.created.get() > 1000, "seed=$seed: control")
        assertEquals("", fake.leaks, "seed=$seed")
        assertEquals(scratch.allocated, scratch.closed.get(), "seed=$seed")
        assertTrue(scratch.blocks.all { it.wipedAtClose }, "seed=$seed")
    }
}
