package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the operating-system-owned objects the FFM layer acquired and released (a `CFTypeRef`, a `LocalFree` buffer, a
 * `GError`, a glib string, a `GList`, a `GObject` reference). A call that leaves [outstanding] above zero leaked, so a test
 * asserts it is zero after every call, including the calls that fail half way. Thread-safe: the binding is used from one
 * native thread, a test reads the counters from another.
 */
internal class NativeLedger {
    private val acquiredCount: AtomicInteger = AtomicInteger()
    private val releasedCount: AtomicInteger = AtomicInteger()

    /** How many objects were acquired since this ledger was created. */
    val acquired: Int get() = acquiredCount.get()

    /** How many objects were released (their release was attempted) since this ledger was created. */
    val released: Int get() = releasedCount.get()

    /** Acquired minus released: what is still held. */
    val outstanding: Int get() = acquiredCount.get() - releasedCount.get()

    /** Opens the scope of one raw call; a test passes its own [arena] to observe memory after the scope closed. */
    fun scope(arena: Arena = Arena.ofConfined()): NativeScope = NativeScope(this, arena)

    internal fun noteAcquired() {
        acquiredCount.incrementAndGet()
    }

    internal fun noteReleased() {
        releasedCount.incrementAndGet()
    }
}

/**
 * The lifetime of one raw call: a confined [Arena] for the native memory the call allocates itself, plus the release
 * actions of the objects the operating system allocated for it. [close] (always in a `finally`/`use`) runs the release
 * actions in reverse order of acquisition, wipes every [secret] buffer, and only then frees the arena, so an object that
 * wraps arena memory (a `CFData` over a key buffer) is released before that memory goes away.
 *
 * The rule for code in this package: the line after an acquisition is [own], so that a failure of any later line (a failing
 * allocation, a failed symbol, an exception from a downcall) frees everything acquired so far. Not thread-safe; one scope
 * belongs to one call on the native thread.
 */
internal class NativeScope(private val ledger: NativeLedger, val arena: Arena = Arena.ofConfined()) : AutoCloseable {

    private val releases: ArrayList<() -> Unit> = ArrayList()
    private val secrets: ArrayList<MemorySegment> = ArrayList()

    /** Registers [release] for [value], an object the operating system allocated; returns [value]. Call it right after the acquisition. */
    fun <T> own(value: T, release: (T) -> Unit): T {
        releases.add { release(value) }
        ledger.noteAcquired()
        return value
    }

    /** A zeroed native buffer of [size] bytes that is wiped before it is freed (a copy of a key or a secret). */
    fun secret(size: Long): MemorySegment {
        val segment: MemorySegment = arena.allocate(size)
        secrets.add(segment)
        return segment
    }

    /** A wiped-on-close native copy of [bytes]. */
    fun secret(bytes: ByteArray): MemorySegment {
        val segment: MemorySegment = secret(bytes.size.toLong())
        MemorySegment.copy(bytes, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, bytes.size)
        return segment
    }

    override fun close() {
        var failure: Throwable? = null
        for (index: Int in releases.indices.reversed()) {
            try {
                releases[index]()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                ledger.noteReleased()
            }
        }
        releases.clear()
        for (segment: MemorySegment in secrets) segment.fill(0)
        secrets.clear()
        try {
            arena.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        if (failure != null) throw failure
    }
}

/** Opens a shared library by an absolute path or a soname. */
internal fun interface LibraryLoader {
    /** The symbols of [name]; throws [IllegalArgumentException] when it cannot be loaded, [IllegalCallerException] when native access is denied. */
    fun load(name: String): SymbolLookup
}

/** One bound native function; `invoke` boxes its arguments (calls are rare and blocking, speed is not the point). */
internal class Downcall(val name: String, private val handle: MethodHandle) {
    /** Calls the function; the result is the boxed return value (`null` for `void`). */
    fun invoke(vararg arguments: Any?): Any? = handle.invokeWithArguments(*arguments)

    /** Calls a function that returns a C `int`. */
    fun int(vararg arguments: Any?): Int = invoke(*arguments) as Int

    /** Calls a function that returns a pointer. */
    fun pointer(vararg arguments: Any?): MemorySegment = invoke(*arguments) as MemorySegment

    /** Calls a function that returns a 64-bit integer. */
    fun long(vararg arguments: Any?): Long = invoke(*arguments) as Long
}

/**
 * The foreign-function plumbing of one binding: how libraries are opened and how a symbol becomes a callable. Tests build
 * one over fake libraries (upcall stubs standing in for native functions) to drive the failure paths of every raw call.
 */
internal class Foreign(
    private val loader: LibraryLoader,
    private val linker: Linker,
) {
    /** Opens [name]; throws what [LibraryLoader.load] throws. */
    fun open(name: String): SymbolLookup = loader.load(name)

    /** Opens [name], or `null` when the library cannot be loaded; a denied native access is still thrown. */
    fun tryOpen(name: String): SymbolLookup? = try {
        loader.load(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The address of [symbol] in [lookup], or `null` when it is not there (never an exception). */
    fun find(lookup: SymbolLookup, symbol: String): MemorySegment? = lookup.find(symbol).orElse(null)

    /** The address of [symbol]; an [UnsatisfiedLinkError] (a [LinkageError]) when it is not there. */
    fun require(lookup: SymbolLookup, symbol: String): MemorySegment =
        find(lookup, symbol) ?: throw UnsatisfiedLinkError("Native symbol $symbol is missing")

    /** Binds [symbol] of [lookup] as a callable with [descriptor]. */
    fun downcall(lookup: SymbolLookup, symbol: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): Downcall =
        Downcall(symbol, linker.downcallHandle(require(lookup, symbol), descriptor, *options))

    /** Binds an already resolved [address] as a callable. */
    fun downcall(name: String, address: MemorySegment, descriptor: FunctionDescriptor, vararg options: Linker.Option): Downcall =
        Downcall(name, linker.downcallHandle(address, descriptor, *options))

    internal companion object {
        /** The foreign layer of the running process: real libraries, the native linker. */
        fun system(): Foreign {
            if (java.lang.foreign.ValueLayout.ADDRESS.byteSize() != 8L) throw UnsatisfiedLinkError("Only 64-bit processes are supported")
            return Foreign(
                loader = LibraryLoader { name: String -> SymbolLookup.libraryLookup(name, Arena.global()) },
                linker = Linker.nativeLinker(),
            )
        }
    }
}
