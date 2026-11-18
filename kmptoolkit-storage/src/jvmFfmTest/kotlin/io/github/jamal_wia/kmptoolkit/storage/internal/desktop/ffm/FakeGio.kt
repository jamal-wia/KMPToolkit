package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The libsecret and glib functions of the backend and default-collection questions, as upcall stubs that model the GIO
 * contract the real libraries keep: `g_main_context_*` with a thread-default stack, `secret_backend_get` that stores a
 * `GAsyncReadyCallback` and delivers it from the main-context iteration (or inside the call, as a completed `GTask` may), and
 * `secret_backend_get_finish` that is valid only inside that callback. Every other name of the real export table of
 * [version] resolves to a dummy address that is bound but never called.
 *
 * Whatever the binding does wrong is written to [violations] instead of thrown (an exception inside an upcall ends the JVM):
 * a pop of a context that is not the thread default, an unref of a context still pushed, an iteration of an unknown context, a
 * `secret_backend_get` with no pushed context, a finish outside the callback, an unref of the borrowed backend, a blocking
 * iteration with nothing that could ever wake it. Single-threaded by design: the binding runs on one native thread.
 */
internal class FakeGio(private val version: String = "0.21.8.2", private val missing: Set<String> = emptySet()) : SymbolLookup, AutoCloseable {

    private val arena: Arena = Arena.ofShared()
    private val linker: Linker = Linker.nativeLinker()
    private val stubs: MutableMap<String, MemorySegment> = HashMap()

    /** Everything the binding did against the contract of the real library. */
    val violations: MutableList<String> = CopyOnWriteArrayList()

    /** Names the binding asked for, in order. */
    val asked: MutableList<String> = CopyOnWriteArrayList()

    // ---- scripts ----
    /** `0`: the callback is delivered inside `secret_backend_get`; `k > 0`: at the k-th iteration (the earlier ones return spuriously). */
    @Volatile var fireAtIteration: Int = 0

    /** Deliver the callback a second time right after the first delivery. */
    @Volatile var duplicateDelivery: Boolean = false

    /** The request ends with a GError (a failure) / with no backend and no error. */
    @Volatile var finishWithError: Boolean = false
    @Volatile var finishWithNothing: Boolean = false

    /** The GType name of the backend instance. */
    @Volatile var backendTypeName: String = "SecretService"

    /** What the default alias holds. */
    @Volatile var defaultCollection: DefaultCollection = DefaultCollection.UNLOCKED

    /** Whether the collection query itself ends with a GError. */
    @Volatile var collectionQueryError: Boolean = false

    enum class DefaultCollection { NONE, LOCKED, UNLOCKED }

    // ---- observations ----
    val contextsCreated: Int get() = contextsCreatedCount
    private var contextsCreatedCount: Int = 0
    private val contextsLive: MutableSet<Long> = HashSet()

    /** The thread-default stack (addresses); empty when every push was popped. */
    val threadDefault: MutableList<Long> = ArrayList()
    var iterations: Int = 0
        private set
    var backendGetCalls: Int = 0
        private set
    var finishCalls: Int = 0
        private set
    var collectionQueries: Int = 0
        private set
    var stackDepthAtCollectionQuery: Int = -1
        private set
    var collectionUnrefs: Int = 0
        private set
    val freedErrors: MutableList<Long> = ArrayList()
    val liveContexts: Set<Long> get() = contextsLive

    private val backend: MemorySegment = arena.allocate(16)
    private val collection: MemorySegment = arena.allocate(16)
    private val callbackResult: MemorySegment = arena.allocate(16)
    private var pendingCallback: MemorySegment = MemorySegment.NULL
    private var pendingUserData: MemorySegment = MemorySegment.NULL
    private var insideCallback: Boolean = false

    init {
        val a = ValueLayout.ADDRESS
        val i = ValueLayout.JAVA_INT
        register("g_main_context_new", "contextNew", FunctionDescriptor.of(a))
        register("g_main_context_push_thread_default", "contextPush", FunctionDescriptor.ofVoid(a))
        register("g_main_context_pop_thread_default", "contextPop", FunctionDescriptor.ofVoid(a))
        register("g_main_context_unref", "contextUnref", FunctionDescriptor.ofVoid(a))
        register("g_main_context_iteration", "contextIteration", FunctionDescriptor.of(i, a, i))
        register("secret_backend_get", "backendGet", FunctionDescriptor.ofVoid(i, a, a, a))
        register("secret_backend_get_finish", "backendGetFinish", FunctionDescriptor.of(a, a, a))
        register("g_type_name_from_instance", "typeName", FunctionDescriptor.of(a, a))
        register("g_object_unref", "objectUnref", FunctionDescriptor.ofVoid(a))
        register("secret_collection_for_alias_sync", "collectionForAlias", FunctionDescriptor.of(a, a, a, i, a, a))
        register("secret_collection_get_locked", "collectionGetLocked", FunctionDescriptor.of(i, a))
        register("g_error_free", "errorFree", FunctionDescriptor.ofVoid(a))
        register("g_quark_to_string", "quarkToString", FunctionDescriptor.of(a, i))
        register("g_dbus_error_get_remote_error", "nullPointer", FunctionDescriptor.of(a, a))
        register("g_dbus_error_encode_gerror", "nullPointer", FunctionDescriptor.of(a, a))
    }

    private fun register(symbol: String, method: String, descriptor: FunctionDescriptor) {
        val handle: MethodHandle = MethodHandles.lookup().findVirtual(FakeGio::class.java, method, descriptor.toMethodType()).bindTo(this)
        stubs[symbol] = linker.upcallStub(handle, descriptor, arena)
    }

    override fun find(name: String): Optional<MemorySegment> {
        asked.add(name)
        if (name in missing || name !in RealLibraryExports.all(version)) return Optional.empty()
        return Optional.of(stubs[name] ?: MemorySegment.ofAddress(DUMMY_ADDRESS))
    }

    private fun violate(text: String) {
        violations.add(text)
    }

    private fun deliver() {
        val handle: MethodHandle = linker.downcallHandle(
            pendingCallback, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val userData: MemorySegment = pendingUserData
        insideCallback = true
        try {
            handle.invokeWithArguments(MemorySegment.NULL, callbackResult, userData)
            if (duplicateDelivery) handle.invokeWithArguments(MemorySegment.NULL, callbackResult, userData)
        } finally {
            insideCallback = false
        }
        pendingCallback = MemorySegment.NULL
    }

    // ---- the stubs (names are looked up by reflection in register) ----

    @Suppress("unused") fun contextNew(): MemorySegment {
        val context: MemorySegment = arena.allocate(16)
        contextsCreatedCount++
        contextsLive.add(context.address())
        return context
    }

    @Suppress("unused") fun contextPush(context: MemorySegment) {
        threadDefault.add(context.address())
    }

    @Suppress("unused") fun contextPop(context: MemorySegment) {
        if (threadDefault.lastOrNull() != context.address()) violate("pop of a context that is not the thread default") else threadDefault.removeAt(threadDefault.lastIndex)
    }

    @Suppress("unused") fun contextUnref(context: MemorySegment) {
        if (context.address() in threadDefault) violate("a context was unreferenced while it was still the thread default")
        if (!contextsLive.remove(context.address())) violate("unref of a context that is not live")
    }

    @Suppress("unused") fun contextIteration(context: MemorySegment, mayBlock: Int): Int {
        iterations++
        if (context.address() !in contextsLive) violate("iteration of a context that is not live")
        if (pendingCallback.address() == 0L) {
            // Nothing is pending: a draining pass answers FALSE, a blocking one would wait for ever.
            if (mayBlock != 0) violate("a blocking iteration with nothing that could wake it")
            return 0
        }
        if (fireAtIteration > 0 && iterations < fireAtIteration) return if (iterations % 2 == 0) 1 else 0
        deliver()
        return 1
    }

    @Suppress("unused") fun backendGet(flags: Int, cancellable: MemorySegment, callback: MemorySegment, userData: MemorySegment) {
        backendGetCalls++
        if (threadDefault.isEmpty()) violate("secret_backend_get without a pushed thread-default context")
        pendingCallback = callback
        pendingUserData = userData
        if (fireAtIteration == 0) deliver()
    }

    @Suppress("unused") fun backendGetFinish(result: MemorySegment, error: MemorySegment): MemorySegment {
        finishCalls++
        if (!insideCallback || result.address() != callbackResult.address()) violate("secret_backend_get_finish outside its callback")
        if (finishWithError) {
            val gerror: MemorySegment = arena.allocate(16)
            gerror.set(ValueLayout.JAVA_INT, 0L, 1)
            gerror.set(ValueLayout.JAVA_INT, 4L, ERROR_CODE)
            error.reinterpret(8).set(ValueLayout.ADDRESS, 0L, gerror)
            return MemorySegment.NULL
        }
        return if (finishWithNothing) MemorySegment.NULL else backend
    }

    @Suppress("unused") fun typeName(instance: MemorySegment): MemorySegment {
        if (instance.address() != backend.address()) violate("g_type_name_from_instance of something that is not the backend")
        return arena.allocateFrom(backendTypeName)
    }

    @Suppress("unused") fun objectUnref(`object`: MemorySegment) {
        when (`object`.address()) {
            backend.address() -> violate("the borrowed backend was unreferenced")
            collection.address() -> collectionUnrefs++
            else -> violate("unref of an unknown object")
        }
    }

    @Suppress("unused") fun collectionForAlias(
        service: MemorySegment, alias: MemorySegment, flags: Int, cancellable: MemorySegment, error: MemorySegment,
    ): MemorySegment {
        collectionQueries++
        stackDepthAtCollectionQuery = threadDefault.size
        if (collectionQueryError) {
            val gerror: MemorySegment = arena.allocate(16)
            gerror.set(ValueLayout.JAVA_INT, 0L, 1)
            gerror.set(ValueLayout.JAVA_INT, 4L, ERROR_CODE)
            error.reinterpret(8).set(ValueLayout.ADDRESS, 0L, gerror)
            return MemorySegment.NULL
        }
        return if (defaultCollection == DefaultCollection.NONE) MemorySegment.NULL else collection
    }

    @Suppress("unused") fun collectionGetLocked(collection: MemorySegment): Int = if (defaultCollection == DefaultCollection.LOCKED) 1 else 0

    @Suppress("unused") fun errorFree(error: MemorySegment) {
        freedErrors.add(error.address())
    }

    @Suppress("unused") fun quarkToString(quark: Int): MemorySegment = arena.allocateFrom(ERROR_DOMAIN)

    @Suppress("unused", "UNUSED_PARAMETER") fun nullPointer(error: MemorySegment): MemorySegment = MemorySegment.NULL

    override fun close() {
        arena.close()
    }

    companion object {
        const val DUMMY_ADDRESS: Long = 0x1000L
        const val ERROR_CODE: Int = 24
        const val ERROR_DOMAIN: String = "g-io-error-quark"
    }
}
