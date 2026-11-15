package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Security.framework and CoreFoundation as upcall stubs, so the FFM mac binding runs on any JDK 25 machine against a library that
 * counts. Every object the fake hands out is in [live] until the binding `CFRelease`s it, which is an accounting independent of
 * the binding's own [NativeLedger]: a leak shows in both, a lying ledger shows in one.
 *
 * Failures are injected as NULL answers ([failAllocation]) or a scripted status: an exception thrown inside an upcall would end the JVM.
 */
internal class FakeSecurityFramework : AutoCloseable {

    private val arena: Arena = Arena.ofShared()
    private val linker: Linker = Linker.nativeLinker()
    private val symbols: MutableMap<String, MemorySegment> = ConcurrentHashMap()

    /** The CF objects handed out and not yet released (address to a description). */
    val live: MutableMap<Long, String> = ConcurrentHashMap()

    /** Every symbol the binding resolved by name, in order. */
    val lookups: MutableList<String> = CopyOnWriteArrayList()

    /** Releases seen for an address that was not live (a double release). */
    val badReleases: MutableList<Long> = CopyOnWriteArrayList()

    /** `n > 0`: the n-th allocation from now answers NULL (then the knob clears); `0`: none fails. */
    @Volatile var failAllocationAt: Int = 0

    /** How many allocations the fake served (failed ones included) since the last [resetAllocationCount]. */
    @Volatile var allocations: Int = 0

    /** The status the next [SecItemCopyMatching] answers. */
    @Volatile var copyStatus: Int = 0
    @Volatile var addStatus: Int = 0
    @Volatile var deleteStatus: Int = 0

    /** The secret [SecItemCopyMatching] returns as a CFData when [copyStatus] is 0. */
    @Volatile var storedSecret: ByteArray = ByteArray(32) { it.toByte() }

    /** The last `CFDataCreateWithBytesNoCopy` buffer content, copied at creation: what the binding handed to the OS. */
    @Volatile var secretSeenByOs: ByteArray? = null

    /** Constant name by the address of the object its global variable holds (so a dictionary entry can be named). */
    private val constantNames: MutableMap<Long, String> = ConcurrentHashMap()

    /** Every `CFDictionarySetValue` as `"key=value"` constant names (an unknown object shows as `?`), since the last [resetSets]. */
    val sets: MutableList<String> = CopyOnWriteArrayList()

    fun resetSets() {
        sets.clear()
    }

    private val dataBytes: MutableMap<Long, MemorySegment> = ConcurrentHashMap()

    val lookup: SymbolLookup = SymbolLookup { name: String ->
        lookups.add(name)
        Optional.ofNullable(symbols[name])
    }

    init {
        // Global variables: the symbol is the address of a variable that holds the object pointer.
        for (name: String in listOf(
            "kCFAllocatorNull", "kCFBooleanTrue", "kCFBooleanFalse", "kSecClass", "kSecClassGenericPassword", "kSecAttrService",
            "kSecAttrAccount", "kSecAttrLabel", "kSecValueData", "kSecReturnData", "kSecReturnAttributes", "kSecMatchLimit",
            "kSecMatchLimitOne", "kSecUseAuthenticationUI", "kSecUseAuthenticationUIFail", "kSecUseDataProtectionKeychain",
        )) {
            val variable: MemorySegment = arena.allocate(8)
            val target: MemorySegment = arena.allocate(8)
            variable.set(ValueLayout.ADDRESS, 0L, target)
            constantNames[target.address()] = name
            symbols[name] = variable
        }
        symbols["kCFTypeDictionaryKeyCallBacks"] = arena.allocate(64)
        symbols["kCFTypeDictionaryValueCallBacks"] = arena.allocate(64)

        val a = ValueLayout.ADDRESS
        val l = ValueLayout.JAVA_LONG
        val i = ValueLayout.JAVA_INT
        val b = ValueLayout.JAVA_BYTE
        stub("CFStringCreateWithBytes", "cfStringCreate", FunctionDescriptor.of(a, a, a, l, i, b),
            MethodType.methodType(MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java, Long::class.java, Int::class.java, Byte::class.java))
        stub("CFDataCreateWithBytesNoCopy", "cfDataCreateNoCopy", FunctionDescriptor.of(a, a, a, l, a),
            MethodType.methodType(MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java, Long::class.java, MemorySegment::class.java))
        stub("CFDictionaryCreateMutable", "cfDictionaryCreate", FunctionDescriptor.of(a, a, l, a, a),
            MethodType.methodType(MemorySegment::class.java, MemorySegment::class.java, Long::class.java, MemorySegment::class.java, MemorySegment::class.java))
        stub("CFDictionarySetValue", "cfDictionarySet", FunctionDescriptor.ofVoid(a, a, a),
            MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java))
        stub("CFRelease", "cfRelease", FunctionDescriptor.ofVoid(a), MethodType.methodType(Void.TYPE, MemorySegment::class.java))
        stub("CFDataGetLength", "cfDataGetLength", FunctionDescriptor.of(l, a), MethodType.methodType(Long::class.java, MemorySegment::class.java))
        stub("CFDataGetBytePtr", "cfDataGetBytePtr", FunctionDescriptor.of(a, a), MethodType.methodType(MemorySegment::class.java, MemorySegment::class.java))
        stub("SecItemCopyMatching", "secItemCopy", FunctionDescriptor.of(i, a, a), MethodType.methodType(Int::class.java, MemorySegment::class.java, MemorySegment::class.java))
        stub("SecItemAdd", "secItemAdd", FunctionDescriptor.of(i, a, a), MethodType.methodType(Int::class.java, MemorySegment::class.java, MemorySegment::class.java))
        stub("SecItemDelete", "secItemDelete", FunctionDescriptor.of(i, a), MethodType.methodType(Int::class.java, MemorySegment::class.java))
    }

    private fun stub(symbol: String, method: String, descriptor: FunctionDescriptor, type: MethodType) {
        val handle = MethodHandles.lookup().findVirtual(FakeSecurityFramework::class.java, method, type).bindTo(this)
        symbols[symbol] = linker.upcallStub(handle, descriptor, arena)
    }

    fun resetAllocationCount() {
        allocations = 0
    }

    private fun take(description: String): MemorySegment {
        allocations++
        if (failAllocationAt > 0 && --failAllocationAt == 0) return MemorySegment.NULL
        val object1: MemorySegment = arena.allocate(16)
        live[object1.address()] = description
        return object1
    }

    @Suppress("unused") fun cfStringCreate(alloc: MemorySegment, bytes: MemorySegment, n: Long, encoding: Int, external: Byte): MemorySegment =
        take("CFStringCreateWithBytes")

    @Suppress("unused") fun cfDataCreateNoCopy(alloc: MemorySegment, bytes: MemorySegment, n: Long, deallocator: MemorySegment): MemorySegment {
        val created: MemorySegment = take("CFDataCreateWithBytesNoCopy")
        if (created.address() != 0L) {
            secretSeenByOs = bytes.reinterpret(n).toArray(ValueLayout.JAVA_BYTE)
        }
        return created
    }

    @Suppress("unused") fun cfDictionaryCreate(alloc: MemorySegment, capacity: Long, key: MemorySegment, value: MemorySegment): MemorySegment =
        take("CFDictionaryCreateMutable")

    @Suppress("unused") fun cfDictionarySet(dictionary: MemorySegment, key: MemorySegment, value: MemorySegment) {
        sets.add("${constantNames[key.address()] ?: "?"}=${constantNames[value.address()] ?: "?"}")
    }

    @Suppress("unused") fun cfRelease(`object`: MemorySegment) {
        if (live.remove(`object`.address()) == null) badReleases.add(`object`.address())
    }

    @Suppress("unused") fun cfDataGetLength(data: MemorySegment): Long = dataBytes[data.address()]?.byteSize() ?: 0L

    @Suppress("unused") fun cfDataGetBytePtr(data: MemorySegment): MemorySegment = dataBytes[data.address()] ?: MemorySegment.NULL

    @Suppress("unused") fun secItemCopy(query: MemorySegment, result: MemorySegment): Int {
        if (copyStatus != 0) return copyStatus
        val data: MemorySegment = take("CFData(copy)")
        if (data.address() == 0L) return -108 // errSecAllocate
        val bytes: MemorySegment = arena.allocate(storedSecret.size.toLong().coerceAtLeast(1L))
        MemorySegment.copy(storedSecret, 0, bytes, ValueLayout.JAVA_BYTE, 0L, storedSecret.size)
        dataBytes[data.address()] = bytes.asSlice(0L, storedSecret.size.toLong())
        result.reinterpret(8).set(ValueLayout.ADDRESS, 0L, data)
        return 0
    }

    @Suppress("unused") fun secItemAdd(attributes: MemorySegment, result: MemorySegment): Int = addStatus

    @Suppress("unused") fun secItemDelete(query: MemorySegment): Int = deleteStatus

    override fun close() {
        arena.close()
    }
}
