package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Breaker round 2 of group sto-jna (ticket item STO-8, plan 3.5 and 4.7: the portal is reported only when libsecret
 * actually selected it): attacks on the round-2 fix of `JnaSecretServiceCalls.backendIsPortal()`, the private GLib
 * main-context recipe around `secret_backend_get`, over the REAL GLib, GObject and GIO of the host (a Linux box, or a Mac
 * with Homebrew's glib). Only libsecret is replaced, by a `GTask` answer, which is how libsecret itself answers.
 *
 * Derived from the KDoc of `selectedBackendIsFileBackend` (a private context pushed as the thread default so the answer is
 * delivered only into this thread's iteration, never into a loop another component runs; popped, drained and released in
 * `finally`; the `SecretBackend` that libsecret caches without a reference of its own is never released) and from GLib's
 * documented `GTask` semantics (a `GTask` may be returned from any thread; it completes on the thread-default context of
 * the thread that created it).
 *
 * Skipped where GLib cannot be loaded, unless `-Pkmptoolkit.secretstore=linux` requires it.
 */
class LibSecretMainContextBreakerR2Test {

    internal interface GioTaskApi : Library {
        fun g_task_new(source: Pointer?, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?): Pointer?
        fun g_task_return_pointer(task: Pointer, result: Pointer?, destroy: Pointer?)
        fun g_task_propagate_pointer(task: Pointer, error: PointerByReference): Pointer?
    }

    internal interface WeakNotify : Callback {
        fun invoke(data: Pointer?, where: Pointer?)
    }

    internal interface SourceFunc : Callback {
        fun invoke(data: Pointer?): Boolean
    }

    internal interface GObjectExtras : Library {
        fun g_object_new_with_properties(type: Long, count: Int, names: Pointer?, values: Pointer?): Pointer?
        fun g_object_weak_ref(instance: Pointer, notify: WeakNotify, data: Pointer?)
        fun g_type_from_name(name: String): Long
        fun g_type_query(type: Long, query: Pointer)
        fun g_type_register_static_simple(parent: Long, name: String, classSize: Int, classInit: Pointer?, instanceSize: Int, instanceInit: Pointer?, flags: Int): Long
    }

    internal interface GLibExtras : Library {
        fun g_main_context_get_thread_default(): Pointer?
        fun g_main_context_default(): Pointer
        fun g_main_loop_new(context: Pointer?, running: Boolean): Pointer
        fun g_main_loop_run(loop: Pointer)
        fun g_main_loop_quit(loop: Pointer)
        fun g_main_loop_unref(loop: Pointer)
        fun g_idle_source_new(): Pointer
        fun g_source_set_callback(source: Pointer, function: SourceFunc, data: Pointer?, destroy: Pointer?)
        fun g_source_attach(source: Pointer, context: Pointer?): Int
        fun g_source_unref(source: Pointer)
    }

    /** The host's real GLib family, or `null` (the test is then skipped, or fails when Linux is required). */
    private class Host(
        val glib: GLibApi,
        val gobject: GObjectApi,
        val gio: GioApi,
        val tasks: GioTaskApi,
        val objects: GObjectExtras,
        val glibExtras: GLibExtras,
        val libraries: List<Pair<NativeLibrary, Class<*>>>,
    )

    private fun loadName(vararg names: String): String? = names.firstOrNull { name: String ->
        try {
            NativeLibrary.getInstance(name)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private fun hostOrSkip(): Host {
        val glibName: String? = loadName("libglib-2.0.so.0", "/opt/homebrew/lib/libglib-2.0.0.dylib", "/usr/local/lib/libglib-2.0.0.dylib")
        val gobjectName: String? = loadName("libgobject-2.0.so.0", "/opt/homebrew/lib/libgobject-2.0.0.dylib", "/usr/local/lib/libgobject-2.0.0.dylib")
        val gioName: String? = loadName("libgio-2.0.so.0", "/opt/homebrew/lib/libgio-2.0.0.dylib", "/usr/local/lib/libgio-2.0.0.dylib")
        if (glibName == null || gobjectName == null || gioName == null) {
            check(System.getProperty("kmptoolkit.secretstore") != "linux") { "kmptoolkit.secretstore=linux: GLib, GObject and GIO must load on this host" }
            Assume.assumeTrue("needs GLib, GObject and GIO (a Linux host, or Homebrew glib on a Mac)", false)
            error("unreachable")
        }
        return Host(
            glib = Native.load(glibName, GLibApi::class.java),
            gobject = Native.load(gobjectName, GObjectApi::class.java),
            gio = Native.load(gioName, GioApi::class.java),
            tasks = Native.load(gioName, GioTaskApi::class.java),
            objects = Native.load(gobjectName, GObjectExtras::class.java),
            glibExtras = Native.load(glibName, GLibExtras::class.java),
            libraries = listOf(
                NativeLibrary.getInstance(glibName) to GLibApi::class.java,
                NativeLibrary.getInstance(gobjectName) to GObjectApi::class.java,
                NativeLibrary.getInstance(gioName) to GioApi::class.java,
            ),
        )
    }

    /** [RawResult.toString] hides the value; this names it. */
    private fun verdict(result: RawResult<Boolean>): String = when (result) {
        is RawResult.Success -> "Success(${result.value})"
        is RawResult.Failure -> "Failure(${result.status})"
    }

    private fun newPlainObject(host: Host): Pointer =
        checkNotNull(host.objects.g_object_new_with_properties(G_TYPE_OBJECT, 0, null, null)) { "g_object_new_with_properties(GObject) failed" }

    private fun callsOver(host: Host, secret: SecretApi): JnaSecretServiceCalls =
        JnaSecretServiceCalls(lazyOf(LinuxPlatform.Ready(LinuxLibraries(host.glib, host.gobject, host.gio, secret, hasBackendApi = true))))

    /** Runs [body] on a fresh thread with a hard bound: a hang is the failure, never a hung build. */
    private fun bounded(name: String, seconds: Long = 60, body: () -> Unit) {
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                body()
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }, name)
        worker.isDaemon = true
        worker.start()
        worker.join(TimeUnit.SECONDS.toMillis(seconds))
        if (worker.isAlive) {
            val stack: String = worker.stackTrace.joinToString("\n  at ")
            throw AssertionError("$name did not finish within $seconds s (a hang: the answer never reached the private context). Stack:\n  at $stack")
        }
        failure.get()?.let { throw AssertionError("$name failed", it) }
    }

    // ---- 1. The glib, gobject and gio names the binding declares exist in the host's real libraries ---------------------

    @Test
    fun `every glib, gobject and gio function the binding declares, the new main-context ones included, is exported by the host GLib`() {
        val host: Host = hostOrSkip()
        val missing: List<String> = host.libraries.flatMap { (library: NativeLibrary, api: Class<*>) ->
            api.declaredMethods.map(Method::getName).distinct().sorted().filter { name: String ->
                try {
                    library.getFunction(name)
                    false
                } catch (_: UnsatisfiedLinkError) {
                    true
                }
            }.map { "${library.name}: $it" }
        }
        // Control: the check sees the new names at all.
        val declared: List<String> = host.libraries.flatMap { (_, api: Class<*>) -> api.declaredMethods.map(Method::getName) }
        assertTrue("g_main_context_push_thread_default" in declared && "g_type_name_from_instance" in declared, "control: the declared names were read")
        assertEquals(emptyList(), missing, "a declared name the real library lacks throws UnsatisfiedLinkError at its first call")
    }

    // ---- 2. The answer arrives from another thread after a GC storm: the callback trampoline must still be alive ----------

    @Test
    fun `an answer returned from another thread after repeated garbage collection reaches the probe, and the GTask is released`() {
        val host: Host = hostOrSkip()
        val finalised = AtomicInteger()
        val finaliser = object : WeakNotify {
            override fun invoke(data: Pointer?, where: Pointer?) {
                finalised.incrementAndGet()
            }
        }
        val answer: Pointer = newPlainObject(host)
        val handOff: LinkedBlockingQueue<Pointer> = LinkedBlockingQueue()
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                // The binding's own callback object goes to native code; only the native trampoline references it from here on.
                val task: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, callback, userData))
                host.objects.g_object_weak_ref(task, finaliser, null)
                handOff.put(task)
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? = host.tasks.g_task_propagate_pointer(result, error)
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        val controller = Thread {
            val task: Pointer = handOff.take()
            repeat(GC_ROUNDS) { System.gc() }
            // GTask: "may be returned from any thread"; it completes on the creating thread's thread-default context.
            host.tasks.g_task_return_pointer(task, answer, null)
            host.gobject.g_object_unref(task)
        }
        controller.isDaemon = true
        controller.start()
        bounded("cross-thread probe") {
            val result: RawResult<Boolean> = calls.backendIsPortal()
            assertEquals("Success(false)", verdict(result), "a plain GObject is not the file backend")
        }
        controller.join(TimeUnit.SECONDS.toMillis(10))
        assertEquals(1, finalised.get(), "the GTask must be finalised once the probe returns: the callback's reference is the last one")
        host.gobject.g_object_unref(answer)
    }

    // ---- 3. A thread that already has its own thread-default context (an app's) keeps it, and the probe never runs it --------

    @Test
    fun `the probe pops only its own context and never dispatches work queued on the caller's thread-default context`() {
        val host: Host = hostOrSkip()
        val appSourceRuns = AtomicInteger()
        val appSource = object : SourceFunc {
            override fun invoke(data: Pointer?): Boolean {
                appSourceRuns.incrementAndGet()
                return false
            }
        }
        val answer: Pointer = newPlainObject(host)
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                val task: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, callback, userData))
                host.tasks.g_task_return_pointer(task, answer, null)
                host.gobject.g_object_unref(task)
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? = host.tasks.g_task_propagate_pointer(result, error)
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        bounded("probe under an app context") {
            val appContext: Pointer = checkNotNull(host.glib.g_main_context_new())
            host.glib.g_main_context_push_thread_default(appContext)
            val source: Pointer = host.glibExtras.g_idle_source_new()
            host.glibExtras.g_source_set_callback(source, appSource, null, null)
            host.glibExtras.g_source_attach(source, appContext)
            try {
                repeat(PROBES_UNDER_APP_CONTEXT) {
                    assertEquals("Success(false)", verdict(calls.backendIsPortal()))
                    assertEquals(
                        Pointer.nativeValue(appContext),
                        Pointer.nativeValue(host.glibExtras.g_main_context_get_thread_default()),
                        "after probe #${it + 1} the caller's own thread-default context must be current again",
                    )
                }
                assertEquals(0, appSourceRuns.get(), "the probe iterated the caller's context: it may only run its private one")
            } finally {
                host.glib.g_main_context_pop_thread_default(appContext)
            }
            // Control: the app's source was really pending and runnable.
            while (host.glib.g_main_context_iteration(appContext, false)) {
                // dispatching the app's own work
            }
            assertEquals(1, appSourceRuns.get(), "control: the app's idle source runs when the app iterates its context")
            host.glibExtras.g_source_unref(source)
            host.glib.g_main_context_unref(appContext)
        }
        host.gobject.g_object_unref(answer)
    }

    // ---- 4. Another thread runs a GLib main loop on the global default context: the answer must not go there -------------

    @Test
    fun `while another thread runs the global default main loop, the answer is still delivered on the probing thread`() {
        val host: Host = hostOrSkip()
        val loop: Pointer = host.glibExtras.g_main_loop_new(null, false)
        val loopRunning = CountDownLatch(1)
        val running = object : SourceFunc {
            override fun invoke(data: Pointer?): Boolean {
                loopRunning.countDown()
                return false
            }
        }
        val start: Pointer = host.glibExtras.g_idle_source_new()
        host.glibExtras.g_source_set_callback(start, running, null, null)
        host.glibExtras.g_source_attach(start, null)
        host.glibExtras.g_source_unref(start)
        val loopThread = Thread { host.glibExtras.g_main_loop_run(loop) }
        loopThread.isDaemon = true
        loopThread.start()
        assertTrue(loopRunning.await(30, TimeUnit.SECONDS), "control: the global default main loop runs on its own thread")

        val deliveredOn = AtomicReference<Thread?>(null)
        val answer: Pointer = newPlainObject(host)
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                // An observing hop that records which thread GLib delivers on, then hands over to the binding's callback.
                val observer = GAsyncReadyCallback { source: Pointer?, result: Pointer?, data: Pointer? ->
                    deliveredOn.set(Thread.currentThread())
                    callback.invoke(source, result, data)
                }
                observers.add(observer)
                val task: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, observer, userData))
                host.tasks.g_task_return_pointer(task, answer, null)
                host.gobject.g_object_unref(task)
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? = host.tasks.g_task_propagate_pointer(result, error)
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        try {
            var prober: Thread? = null
            bounded("probe beside a running default loop") {
                prober = Thread.currentThread()
                assertEquals("Success(false)", verdict(calls.backendIsPortal()))
            }
            assertEquals(prober, deliveredOn.get(), "the answer was dispatched by ${deliveredOn.get()?.name}, not inside the probe's own iteration")
        } finally {
            host.glibExtras.g_main_loop_quit(loop)
            loopThread.join(TimeUnit.SECONDS.toMillis(10))
            host.glibExtras.g_main_loop_unref(loop)
            host.gobject.g_object_unref(answer)
            observers.clear()
        }
    }

    // ---- 5. libsecret's ownership of the backend, over a real GObject: alive after every probe, at most one ref per probe ----

    @Test
    fun `a real GObject backend cached the libsecret way survives repeated probes, costs at most one reference per probe, and is recognised by its type name`() {
        val host: Host = hostOrSkip()
        Assume.assumeTrue(
            "libsecret's own SecretFileBackend type is registered in this process; a stand-in of that name cannot be made",
            host.objects.g_type_from_name(FILE_BACKEND_TYPE) == 0L,
        )
        val query = Memory(G_TYPE_QUERY_SIZE)
        host.objects.g_type_query(G_TYPE_OBJECT, query)
        val classSize: Int = query.getInt(16L)
        val instanceSize: Int = query.getInt(20L)
        val fileBackendType: Long = host.objects.g_type_register_static_simple(G_TYPE_OBJECT, FILE_BACKEND_TYPE, classSize, null, instanceSize, null, 0)
        assertTrue(fileBackendType != 0L, "control: a GObject type named $FILE_BACKEND_TYPE was registered")

        val finalised = AtomicInteger()
        val finaliser = object : WeakNotify {
            override fun invoke(data: Pointer?, where: Pointer?) {
                finalised.incrementAndGet()
            }
        }
        val cache = AtomicReference<Pointer?>(null)
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                val task: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, callback, userData))
                host.tasks.g_task_return_pointer(task, null, null)
                host.gobject.g_object_unref(task)
            }

            // secret-backend.c (0.20.0 to main): a new backend is stored in backend_instance WITHOUT a reference of its own
            // and returned; a cached one is returned with g_object_ref.
            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? {
                host.tasks.g_task_propagate_pointer(result, error)
                val cached: Pointer? = cache.get()
                if (cached != null) return host.gobject.g_object_ref(cached)
                val created: Pointer = checkNotNull(host.objects.g_object_new_with_properties(fileBackendType, 0, null, null))
                host.objects.g_object_weak_ref(created, finaliser, null)
                cache.set(created)
                return created
            }
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        bounded("backend ownership") {
            repeat(OWNERSHIP_PROBES) { probe: Int ->
                assertEquals("Success(true)", verdict(calls.backendIsPortal()), "probe #${probe + 1}: SecretFileBackend is the portal")
                assertEquals(0, finalised.get(), "probe #${probe + 1} freed libsecret's cached backend: the next libsecret call would use freed memory")
            }
            assertEquals("Success(false)", verdict(calls.defaultCollectionLocked()), "the file backend's collection is never locked")
        }
        val backend: Pointer = checkNotNull(cache.get())
        // GObject: GTypeInstance (one pointer), then guint ref_count.
        val refs: Int = backend.getInt(Native.POINTER_SIZE.toLong())
        val callsMade: Int = OWNERSHIP_PROBES + 1
        assertTrue(refs in 1..callsMade, "the backend holds $refs references after $callsMade backend questions: at most one each, never more")
        // Control of the weak notify itself: dropping every reference finalises the object exactly once.
        repeat(refs) { host.gobject.g_object_unref(backend) }
        assertEquals(1, finalised.get(), "control: the backend is finalised once its references are gone")
    }

    // ---- 6. A seeded storm on real GLib: many threads, answers from the own thread or from another one --------------------

    @Test
    fun `eight threads probing at once over real GLib each get their own answer and leave no GTask alive (seed 5231)`() {
        val host: Host = hostOrSkip()
        val seed = 5231
        val finalised = AtomicInteger()
        val created = AtomicInteger()
        val finaliser = object : WeakNotify {
            override fun invoke(data: Pointer?, where: Pointer?) {
                finalised.incrementAndGet()
            }
        }
        val answer: Pointer = newPlainObject(host)
        val remote: LinkedBlockingQueue<Pointer> = LinkedBlockingQueue()
        val remoteAnswers = AtomicInteger()
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                val task: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, callback, userData))
                host.objects.g_object_weak_ref(task, finaliser, null)
                created.incrementAndGet()
                if (fromAnotherThread.get()) {
                    remote.put(task)
                } else {
                    host.tasks.g_task_return_pointer(task, answer, null)
                    host.gobject.g_object_unref(task)
                }
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? = host.tasks.g_task_propagate_pointer(result, error)
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        val answering = Thread {
            while (true) {
                val task: Pointer = remote.take()
                if (Pointer.nativeValue(task) == 0L) return@Thread
                host.tasks.g_task_return_pointer(task, answer, null)
                host.gobject.g_object_unref(task)
                remoteAnswers.incrementAndGet()
            }
        }
        answering.isDaemon = true
        answering.start()
        val failures: LinkedBlockingQueue<String> = LinkedBlockingQueue()
        val workers: List<Thread> = (0 until STORM_THREADS).map { worker: Int ->
            Thread {
                val random = Random(seed * 1000 + worker)
                repeat(STORM_PROBES) { probe: Int ->
                    fromAnotherThread.set(random.nextBoolean())
                    val result: RawResult<Boolean> = calls.backendIsPortal()
                    if (verdict(result) != "Success(false)") failures.add("seed=$seed worker=$worker probe=$probe: ${verdict(result)}")
                }
            }.also { it.isDaemon = true }
        }
        workers.forEach(Thread::start)
        for (worker: Thread in workers) worker.join(TimeUnit.SECONDS.toMillis(60))
        val hung: List<String> = workers.filter(Thread::isAlive).map { it.name + "\n  at " + it.stackTrace.joinToString("\n  at ") }
        remote.put(Pointer.createConstant(0L))
        answering.join(TimeUnit.SECONDS.toMillis(10))
        assertEquals(emptyList(), hung, "seed=$seed: probing threads hung (an answer was delivered to a context nobody iterates)")
        assertEquals(emptyList(), failures.toList(), "seed=$seed")
        val total: Int = STORM_THREADS * STORM_PROBES
        assertEquals(total, created.get(), "seed=$seed control: every probe asked libsecret")
        assertTrue(remoteAnswers.get() > 0, "seed=$seed control: some answers came from another thread")
        assertEquals(total, finalised.get(), "seed=$seed: every GTask must be released by its probe")
        host.gobject.g_object_unref(answer)
    }

    // ---- 7. A two-hop answer (libsecret creating its backend) and work left pending on the private context --------------

    @Test
    fun `an answer that needs two hops is awaited, and work left pending on the private context is run before it is released`() {
        val host: Host = hostOrSkip()
        val leftover = AtomicInteger()
        val leftoverSource = object : SourceFunc {
            override fun invoke(data: Pointer?): Boolean {
                leftover.incrementAndGet()
                return false
            }
        }
        val answer: Pointer = newPlainObject(host)
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                // Hop 1 (like g_async_initable_new_async creating the backend): an inner task on the same thread-default context.
                val inner = GAsyncReadyCallback { _: Pointer?, _: Pointer?, _: Pointer? ->
                    // Inside the probe's iteration: start hop 2, the answer, and leave one unrelated source behind.
                    val outer: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, callback, userData))
                    host.tasks.g_task_return_pointer(outer, answer, null)
                    host.gobject.g_object_unref(outer)
                    val source: Pointer = host.glibExtras.g_idle_source_new()
                    host.glibExtras.g_source_set_callback(source, leftoverSource, null, null)
                    host.glibExtras.g_source_attach(source, host.glibExtras.g_main_context_get_thread_default())
                    host.glibExtras.g_source_unref(source)
                }
                observers.add(inner)
                val first: Pointer = checkNotNull(host.tasks.g_task_new(null, cancellable, inner, null))
                host.tasks.g_task_return_pointer(first, null, null)
                host.gobject.g_object_unref(first)
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? = host.tasks.g_task_propagate_pointer(result, error)
        }
        val calls: JnaSecretServiceCalls = callsOver(host, secret)
        try {
            bounded("two-hop probe") {
                assertEquals("Success(false)", verdict(calls.backendIsPortal()))
            }
            assertEquals(1, leftover.get(), "the source left on the private context must be run by the drain, once, before the context is released")
        } finally {
            observers.clear()
            host.gobject.g_object_unref(answer)
        }
    }

    private companion object {
        /** `G_TYPE_OBJECT`: `G_TYPE_MAKE_FUNDAMENTAL(20)`. */
        const val G_TYPE_OBJECT: Long = 80L

        /** `GTypeQuery`: GType, const gchar *, guint class_size, guint instance_size (64-bit). */
        const val G_TYPE_QUERY_SIZE: Long = 24L
        const val FILE_BACKEND_TYPE: String = JnaSecretServiceCalls.FILE_BACKEND_TYPE
        const val GC_ROUNDS: Int = 5
        const val PROBES_UNDER_APP_CONTEXT: Int = 3
        const val OWNERSHIP_PROBES: Int = 5
        const val STORM_THREADS: Int = 8
        const val STORM_PROBES: Int = 25

        /** Per probing thread: whether the next answer comes from the answering thread (set from the worker's seeded random). */
        val fromAnotherThread: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        /** Test-owned callbacks handed to native code, kept strongly reachable until GLib can no longer call them. */
        val observers: MutableList<GAsyncReadyCallback> = java.util.Collections.synchronizedList(ArrayList())
    }
}
