package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a fake CoreFoundation object is. */
internal sealed interface CfObject {
    class Text(val value: String) : CfObject
    class Dict(val entries: MutableMap<String, CfObject> = LinkedHashMap()) : CfObject
    /**
     * A `CFData`. [returnedBySystem] marks the immutable object a `SecItemCopyMatching` hands out (decisions.md D-31: its bytes are
     * read-only by Apple's contract, so the binding must neither write into them nor ask for a mutable pointer to them);
     * [original] is what the system put there, compared at release into [unchangedAtRelease].
     */
    class Data(
        val buffer: Memory = Memory(256),
        var length: Long = 0L,
        var wipedAtRelease: Boolean = false,
        val returnedBySystem: Boolean = false,
        var original: ByteArray? = null,
        var unchangedAtRelease: Boolean = true,
    ) : CfObject
    class Constant(val name: String) : CfObject
}

/**
 * A scriptable CoreFoundation and Security.framework. Every object a call creates lives in [objects] until it is released,
 * so what a call leaves behind is [live]; releasing a system constant or a handle twice is an error, as it would be a crash.
 */
internal class FakeMacLibraries(private val missingSymbols: Set<String> = emptySet()) {
    val objects: HandleTable<CfObject> = HandleTable(0x1000L)
    val queries: CopyOnWriteArrayList<CfObject.Dict> = CopyOnWriteArrayList()
    val addedDictionaries: CopyOnWriteArrayList<CfObject.Dict> = CopyOnWriteArrayList()
    val deletedDictionaries: CopyOnWriteArrayList<CfObject.Dict> = CopyOnWriteArrayList()

    /** The `CFData` objects the binding created itself (the mutable copy of a key an add hands over) and released. */
    val releasedData: CopyOnWriteArrayList<CfObject.Data> = CopyOnWriteArrayList()

    /** The `CFData` objects a `SecItemCopyMatching` handed out and the binding released (never written to, D-31). */
    val returnedData: CopyOnWriteArrayList<CfObject.Data> = CopyOnWriteArrayList()

    /** Calls of `CFDataGetMutableBytePtr` on a system-returned (immutable) `CFData`: always a violation. */
    val mutableAccessToReturnedData: AtomicInteger = AtomicInteger()
    val security: AtomicInteger = AtomicInteger()

    /** What `SecItemCopyMatching` answers; `null` data means a failure status without a result. */
    @Volatile var copyScript: (CfObject.Dict) -> Pair<Int, ByteArray?> = { 0 to ByteArray(32) { it.toByte() } }
    @Volatile var addStatus: Int = 0
    @Volatile var deleteStatus: Int = 0
    @Volatile var throwFromSecurity: Throwable? = null

    private val constants: HashMap<String, Pointer> = HashMap()
    private val symbolMemory: ArrayList<Memory> = ArrayList()

    private fun constantRef(name: String): Pointer = synchronized(constants) {
        constants.getOrPut(name) { Pointer(CONSTANT_BASE + constants.size * 16L).also { constantObjects[Pointer.nativeValue(it)] = CfObject.Constant(name) } }
    }

    private val constantObjects: java.util.concurrent.ConcurrentHashMap<Long, CfObject.Constant> = java.util.concurrent.ConcurrentHashMap()

    private fun resolve(pointer: Pointer): CfObject = constantObjects[Pointer.nativeValue(pointer)] ?: objects.get(pointer)

    val symbols: GlobalSymbols = GlobalSymbols { name: String ->
        if (name in missingSymbols) null
        else if (name.startsWith("kCFType")) Pointer(CONSTANT_BASE * 2 + name.length)
        else synchronized(symbolMemory) {
            val cell = Memory(8)
            cell.setPointer(0L, constantRef(name))
            symbolMemory.add(cell)
            cell
        }
    }

    val coreFoundation: CoreFoundationApi = object : CoreFoundationApi {
        override fun CFStringCreateWithBytes(allocator: Pointer?, bytes: Pointer, length: Long, encoding: Int, isExternal: Boolean): Pointer? {
            assertEquals(JnaMacKeychainCalls.UTF8_ENCODING, encoding)
            return objects.create(CfObject.Text(String(bytes.getByteArray(0L, length.toInt()), Charsets.UTF_8)))
        }

        override fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: Long, keyCallBacks: Pointer?, valueCallBacks: Pointer?): Pointer? {
            assertNotNull(keyCallBacks, "a dictionary of CF types needs the key callbacks")
            assertNotNull(valueCallBacks, "a dictionary of CF types needs the value callbacks")
            return objects.create(CfObject.Dict())
        }

        override fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer) {
            val dict = objects.get(dictionary) as CfObject.Dict
            dict.entries[(resolve(key) as CfObject.Constant).name] = resolve(value)
        }

        override fun CFDataCreateMutable(allocator: Pointer?, capacity: Long): Pointer? = objects.create(CfObject.Data())

        override fun CFDataAppendBytes(data: Pointer, bytes: Pointer, length: Long) {
            val target = objects.get(data) as CfObject.Data
            target.buffer.write(target.length, bytes.getByteArray(0L, length.toInt()), 0, length.toInt())
            target.length += length
        }

        override fun CFDataGetMutableBytePtr(data: Pointer): Pointer? {
            val target = objects.get(data) as CfObject.Data
            if (target.returnedBySystem) mutableAccessToReturnedData.incrementAndGet()
            return target.buffer
        }

        override fun CFDataGetBytePtr(data: Pointer): Pointer? = (objects.get(data) as CfObject.Data).buffer

        override fun CFDataGetLength(data: Pointer): Long = (objects.get(data) as CfObject.Data).length

        override fun CFRelease(cf: Pointer) {
            val released: CfObject = objects.release(cf)
            if (released is CfObject.Data) {
                if (released.returnedBySystem) {
                    val expected: ByteArray = released.original ?: ByteArray(0)
                    released.unchangedAtRelease = released.buffer.getByteArray(0L, expected.size).contentEquals(expected)
                    returnedData.add(released)
                } else {
                    released.wipedAtRelease = released.buffer.getByteArray(0L, 256).all { it == 0.toByte() }
                    releasedData.add(released)
                }
            }
        }
    }

    val securityApi: SecurityApi = object : SecurityApi {
        override fun SecItemCopyMatching(query: Pointer, result: PointerByReference?): Int {
            security.incrementAndGet()
            throwFromSecurity?.let { throw it }
            val dict = objects.get(query) as CfObject.Dict
            queries.add(dict.copyShallow())
            val (status: Int, data: ByteArray?) = copyScript(dict)
            if (data != null && status == 0 && !dict.entries.containsKey("kSecReturnData")) {
                // An attribute-only query answers with a dictionary of attributes, never the secret.
                result?.value = objects.create(CfObject.Dict())
            } else if (data != null && status == 0) {
                val created = CfObject.Data(returnedBySystem = true, original = data.copyOf())
                created.buffer.write(0L, data, 0, data.size)
                created.length = data.size.toLong()
                result?.value = objects.create(created)
            }
            return status
        }

        override fun SecItemAdd(attributes: Pointer, result: Pointer?): Int {
            security.incrementAndGet()
            throwFromSecurity?.let { throw it }
            addedDictionaries.add((objects.get(attributes) as CfObject.Dict).copyShallow(snapshotData = true))
            return addStatus
        }

        override fun SecItemDelete(query: Pointer): Int {
            security.incrementAndGet()
            throwFromSecurity?.let { throw it }
            deletedDictionaries.add((objects.get(query) as CfObject.Dict).copyShallow())
            return deleteStatus
        }
    }

    val platform: MacPlatform = MacPlatform(coreFoundation, securityApi, symbols)

    /** The remaining live objects (the query dictionaries, strings, data): what a call forgot to release. */
    val live: Int get() = objects.live

    private fun CfObject.Dict.copyShallow(snapshotData: Boolean = false): CfObject.Dict {
        val copy = CfObject.Dict()
        for ((key: String, value: CfObject) in entries) {
            copy.entries[key] = when {
                value is CfObject.Text -> CfObject.Text(value.value)
                value is CfObject.Data && snapshotData ->
                    CfObject.Data(Memory(256).also { it.write(0L, value.buffer.getByteArray(0L, 256), 0, 256) }, value.length)
                else -> value
            }
        }
        return copy
    }

    companion object {
        const val CONSTANT_BASE: Long = 0x7000_0000L
    }
}

internal fun CfObject.Dict.text(key: String): String? = (entries[key] as? CfObject.Text)?.value

internal fun CfObject.Dict.constantName(key: String): String? = (entries[key] as? CfObject.Constant)?.name

/** The macOS call object over the scripted frameworks: what it asks of Security.framework, and what it releases. */
class MacKeychainCallsTest {

    private val secret: ByteArray = ByteArray(32) { (it + 1).toByte() }

    private fun calls(fake: FakeMacLibraries, scratch: RecordingScratch): JnaMacKeychainCalls =
        JnaMacKeychainCalls(lazyOf(fake.platform), scratch)

    @Test
    fun `a read asks for the generic password at its service and account, in the file keychain, and gives back the bytes`() {
        val fake = FakeMacLibraries()
        val scratch = RecordingScratch()
        val result: RawResult<ByteArray?> = calls(fake, scratch).copyGenericPassword("svc ünï", "dir-0123", allowUi = true)

        assertContentEquals(ByteArray(32) { it.toByte() }, assertIs<RawResult.Success<ByteArray?>>(result).value)
        val query: CfObject.Dict = fake.queries.single()
        assertEquals("kSecClassGenericPassword", query.constantName("kSecClass"))
        assertEquals("svc ünï", query.text("kSecAttrService"), "the service is passed as UTF-8")
        assertEquals("dir-0123", query.text("kSecAttrAccount"))
        assertEquals("kCFBooleanTrue", query.constantName("kSecReturnData"))
        assertEquals("kSecMatchLimitOne", query.constantName("kSecMatchLimit"))
        assertEquals("kCFBooleanFalse", query.constantName("kSecUseDataProtectionKeychain"), "the login (file) keychain, explicitly")
        assertFalse(query.entries.containsKey("kSecUseAuthenticationUI"), "an interactive read does not suppress the dialog")
        assertEquals(0, fake.live, "every CoreFoundation object the call created is released, the result included")
        assertEquals(1, fake.returnedData.size, "control: the result data existed and was released")
        assertTrue(fake.returnedData.single().original!!.isNotEmpty(), "control: the result data held a key")
        assertTrue(
            fake.returnedData.single().unchangedAtRelease,
            "the data a read returns is read-only by Apple's contract (D-31): the binding copies it out and writes nothing into it",
        )
        assertEquals(0, fake.mutableAccessToReturnedData.get(), "the binding never asks for a mutable pointer to the returned data")
        assertTrue(fake.releasedData.isEmpty(), "a read creates no CFData of its own")
        scratch.assertAllFreedAndWiped("read")
    }

    @Test
    fun `a non-interactive read passes UIFail and never the skip flag`() {
        val fake = FakeMacLibraries()
        calls(fake, RecordingScratch()).copyGenericPassword("svc", "acct", allowUi = false)

        val query: CfObject.Dict = fake.queries.single()
        assertEquals("kSecUseAuthenticationUIFail", query.constantName("kSecUseAuthenticationUI"))
        assertFalse(query.entries.values.any { it is CfObject.Constant && it.name.contains("Skip") }, "the skip flag silently omits items and answers -25300")
        assertEquals(0, fake.live)
    }

    @Test
    fun `a status is reported as a failure carrying it, and a not-found leaves nothing behind`() {
        for (status: Int in listOf(-25300, -25308, -128, -25293, -25307, -34018, -1)) {
            val fake = FakeMacLibraries()
            fake.copyScript = { status to null }
            val scratch = RecordingScratch()
            val result: RawResult<ByteArray?> = calls(fake, scratch).copyGenericPassword("svc", "acct", true)
            assertEquals(RawStatus(status), assertIs<RawResult.Failure>(result).status)
            assertEquals(0, fake.live, "status $status leaves no object behind")
            scratch.assertAllFreedAndWiped("status $status")
        }
    }

    @Test
    fun `success without data is a failure, not a null secret`() {
        val fake = FakeMacLibraries()
        fake.copyScript = { 0 to null }
        val result = calls(fake, RecordingScratch()).copyGenericPassword("svc", "acct", true)
        assertEquals(JnaMacKeychainCalls.ERR_SEC_INTERNAL_COMPONENT, assertIs<RawResult.Failure>(result).status.code)
        assertEquals(0, fake.live)
    }

    @Test
    fun `an add carries the label and the key, zeroes every native copy of the key and releases everything`() {
        val fake = FakeMacLibraries()
        val scratch = RecordingScratch()
        val status: Int = calls(fake, scratch).addGenericPassword("svc", "acct", "My label", secret, allowUi = true)

        assertEquals(0, status)
        val added: CfObject.Dict = fake.addedDictionaries.single()
        assertEquals("My label", added.text("kSecAttrLabel"))
        assertEquals("svc", added.text("kSecAttrService"))
        assertEquals("kSecClassGenericPassword", added.constantName("kSecClass"))
        assertEquals("kCFBooleanFalse", added.constantName("kSecUseDataProtectionKeychain"))
        val data = added.entries["kSecValueData"] as CfObject.Data
        assertContentEquals(secret, data.buffer.getByteArray(0L, 32), "the key reached Security.framework intact")
        assertEquals(0, fake.live)
        assertTrue(fake.releasedData.single().wipedAtRelease, "the CFData that held the key is zeroed before it is released")
        scratch.assertAllFreedAndWiped("add")
        assertContentEquals(ByteArray(32) { (it + 1).toByte() }, secret, "the caller's array is never changed by the binding")
    }

    @Test
    fun `an add that fails still wipes and releases, and reports its status`() {
        val fake = FakeMacLibraries()
        fake.addStatus = -25299
        val scratch = RecordingScratch()
        assertEquals(-25299, calls(fake, scratch).addGenericPassword("svc", "acct", "l", secret, allowUi = true))
        assertEquals(0, fake.live)
        assertTrue(fake.releasedData.all { it.wipedAtRelease })
        scratch.assertAllFreedAndWiped("failed add")
    }

    @Test
    fun `a delete addresses only the service and account`() {
        val fake = FakeMacLibraries()
        fake.deleteStatus = -25300
        assertEquals(-25300, calls(fake, RecordingScratch()).deleteGenericPassword("svc", "acct", allowUi = true))
        val query: CfObject.Dict = fake.deletedDictionaries.single()
        assertEquals(setOf("kSecClass", "kSecAttrService", "kSecAttrAccount", "kSecUseDataProtectionKeychain"), query.entries.keys)
        assertEquals(0, fake.live)
    }

    @Test
    fun `a non-interactive add and delete pass UIFail, an interactive one does not, and a macOS that cannot suppress the dialog never calls`() {
        val fake = FakeMacLibraries()
        val calls: JnaMacKeychainCalls = calls(fake, RecordingScratch())
        assertEquals(0, calls.addGenericPassword("svc", "acct", "l", secret, allowUi = false))
        assertEquals(0, calls.deleteGenericPassword("svc", "acct", allowUi = false))
        assertEquals("kSecUseAuthenticationUIFail", fake.addedDictionaries.single().constantName("kSecUseAuthenticationUI"))
        assertEquals("kSecUseAuthenticationUIFail", fake.deletedDictionaries.single().constantName("kSecUseAuthenticationUI"))
        assertEquals(0, calls.addGenericPassword("svc", "acct", "l", secret, allowUi = true))
        assertFalse(fake.addedDictionaries.last().entries.containsKey("kSecUseAuthenticationUI"))
        assertEquals(0, fake.live)

        val noUi = FakeMacLibraries(missingSymbols = setOf("kSecUseAuthenticationUI"))
        val scratch = RecordingScratch()
        val blocked: JnaMacKeychainCalls = calls(noUi, scratch)
        assertEquals(JnaMacKeychainCalls.ERR_SEC_NOT_AVAILABLE, blocked.addGenericPassword("svc", "acct", "l", secret, allowUi = false))
        assertEquals(JnaMacKeychainCalls.ERR_SEC_NOT_AVAILABLE, blocked.deleteGenericPassword("svc", "acct", allowUi = false))
        assertEquals(0, noUi.security.get(), "no keychain call that could show a dialog is made")
        assertEquals(0, noUi.live)
        assertEquals(scratch.allocated, scratch.closed.get())
    }

    @Test
    fun `a probe never prompts whatever it is asked, and releases a result it got`() {
        for (allowUi: Boolean in listOf(true, false)) {
            val fake = FakeMacLibraries()
            fake.copyScript = { 0 to ByteArray(4) }
            val status: Int = calls(fake, RecordingScratch()).probe(allowUi)
            assertEquals(0, status)
            val query: CfObject.Dict = fake.queries.single()
            assertEquals("kSecUseAuthenticationUIFail", query.constantName("kSecUseAuthenticationUI"), "allowUi=$allowUi: the probe never prompts")
            assertEquals("kCFBooleanTrue", query.constantName("kSecReturnAttributes"))
            assertFalse(query.entries.containsKey("kSecReturnData"), "the probe is attribute-only: it never reads a secret")
            assertFalse(query.entries.containsKey("kSecAttrAccount"))
            assertEquals(0, fake.live, "allowUi=$allowUi")
        }
    }

    @Test
    fun `a macOS without the UI constant never reads with the dialog possible`() {
        val fake = FakeMacLibraries(missingSymbols = setOf("kSecUseAuthenticationUI"))
        val calls: JnaMacKeychainCalls = calls(fake, RecordingScratch())
        val result = calls.copyGenericPassword("svc", "acct", allowUi = false)
        assertEquals(JnaMacKeychainCalls.ERR_SEC_NOT_AVAILABLE, assertIs<RawResult.Failure>(result).status.code)
        assertEquals(0, fake.security.get(), "a non-interactive read that cannot suppress the dialog is not made")
        assertEquals(JnaMacKeychainCalls.ERR_SEC_NOT_AVAILABLE, calls.probe(true))
        assertEquals(0, fake.live, "the early return still releases what it created")
        assertIs<RawResult.Success<ByteArray?>>(calls.copyGenericPassword("svc", "acct", allowUi = true))
    }

    @Test
    fun `a macOS without the data-protection constant still works against the file keychain`() {
        val fake = FakeMacLibraries(missingSymbols = setOf("kSecUseDataProtectionKeychain"))
        assertIs<RawResult.Success<ByteArray?>>(calls(fake, RecordingScratch()).copyGenericPassword("svc", "acct", true))
        assertFalse(fake.queries.single().entries.containsKey("kSecUseDataProtectionKeychain"))
    }

    @Test
    fun `a missing required constant is a link error, and nothing leaks`() {
        val fake = FakeMacLibraries(missingSymbols = setOf("kSecClassGenericPassword"))
        val scratch = RecordingScratch()
        assertFailsWith<UnsatisfiedLinkError> { calls(fake, scratch).copyGenericPassword("svc", "acct", true) }
        assertEquals(0, fake.live, "the dictionary created before the constant was looked up is released")
        assertEquals(scratch.allocated, scratch.closed.get())
    }

    @Test
    fun `a link error thrown by the framework leaves no object and no key copy behind`() {
        val fake = FakeMacLibraries()
        fake.throwFromSecurity = NoSuchMethodError("SecItemAdd")
        val scratch = RecordingScratch()
        assertFailsWith<NoSuchMethodError> { calls(fake, scratch).addGenericPassword("svc", "acct", "l", secret, allowUi = true) }
        assertEquals(0, fake.live, "the CoreFoundation objects are released on the throwing path")
        assertTrue(fake.releasedData.single().wipedAtRelease, "the key data is wiped on the throwing path")
        scratch.assertAllFreedAndWiped("throwing add")
        assertFailsWith<NoSuchMethodError> { calls(fake, RecordingScratch()).copyGenericPassword("svc", "acct", true) }
        assertEquals(0, fake.live)
    }

    @Test
    fun `a thousand mixed operations leave nothing alive, and many threads do not interfere (seed 5101)`() {
        val seed = 5101
        val fake = FakeMacLibraries()
        val scratch = RecordingScratch()
        val calls: JnaMacKeychainCalls = calls(fake, scratch)
        val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val start = CountDownLatch(1)
        val threads: List<Thread> = (0 until 8).map { worker: Int ->
            Thread {
                val random = Random(seed * 1000 + worker)
                start.await()
                repeat(125) {
                    try {
                        when (random.nextInt(4)) {
                            0 -> calls.copyGenericPassword("svc$worker", "acct", random.nextBoolean())
                            1 -> calls.addGenericPassword("svc$worker", "acct", "l", secret, random.nextBoolean())
                            2 -> calls.deleteGenericPassword("svc$worker", "acct", random.nextBoolean())
                            else -> calls.probe(random.nextBoolean())
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
        assertTrue(fake.objects.created.get() > 1000, "seed=$seed: control, the storm created objects")
        assertEquals(0, fake.live, "seed=$seed: nothing left alive after 1000 operations")
        assertEquals(fake.objects.created.get(), fake.objects.released.get(), "seed=$seed")
        assertTrue(fake.releasedData.all { it.wipedAtRelease }, "seed=$seed: every released CFData was zero")
        assertTrue(fake.returnedData.all { it.unchangedAtRelease }, "seed=$seed: a returned CFData was written to")
        assertEquals(0, fake.mutableAccessToReturnedData.get(), "seed=$seed")
        assertEquals(scratch.allocated, scratch.closed.get(), "seed=$seed: every scratch block was freed")
        assertTrue(scratch.blocks.all { it.wipedAtClose }, "seed=$seed")
    }
}
