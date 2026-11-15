package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The private-main-context recipe of `JnaSecretServiceCalls.backendIsPortal()` over the REAL GLib and GIO of the host (a
 * Linux box, or a Mac with Homebrew's glib), with the real JNA callback trampoline and a real `GTask`. Only libsecret is
 * replaced: `secret_backend_get` becomes `g_task_new` + `g_task_return_pointer` and `secret_backend_get_finish` becomes
 * `g_task_propagate_pointer`, which is how libsecret itself answers. It proves what the scripted fake cannot:
 *
 * - the answer reaches the callback only inside an iteration of the context the code pushed (a `GTask` delivers on the
 *   thread-default context of its creation; without the push the answer goes to the global default context, nobody
 *   iterates it, and the call would never return: the test fails on a hard time bound instead of hanging);
 * - the real `GAsyncReadyCallback` trampoline built from a Kotlin object works, and its `GAsyncResult` is released (the
 *   `GTask` is finalised: a `GWeakNotify` counts it);
 * - the thread-default context is popped (a following push/pop pair on the same thread behaves).
 *
 * Skipped where GLib cannot be loaded, unless `-Pkmptoolkit.secretstore=linux` requires it.
 */
class RealGlibMainContextTest {

    internal interface GioTaskApi : Library {
        fun g_task_new(source: Pointer?, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?): Pointer?
        fun g_task_return_pointer(task: Pointer, result: Pointer?, destroy: Pointer?)
        fun g_task_propagate_pointer(task: Pointer, error: PointerByReference): Pointer?
    }

    internal interface WeakNotify : Callback {
        fun invoke(data: Pointer?, where: Pointer?)
    }

    internal interface GObjectExtras : Library {
        fun g_object_new(type: Long, firstProperty: Pointer?): Pointer?
        fun g_object_weak_ref(instance: Pointer, notify: WeakNotify, data: Pointer?)
    }

    private fun load(vararg names: String): String? = names.firstOrNull { name: String ->
        try {
            NativeLibrary.getInstance(name)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Test
    fun `the backend question reaches the callback inside the private context and releases everything over real GLib`() {
        val glibName: String? = load("libglib-2.0.so.0", "/opt/homebrew/lib/libglib-2.0.0.dylib", "/usr/local/lib/libglib-2.0.0.dylib")
        val gobjectName: String? = load("libgobject-2.0.so.0", "/opt/homebrew/lib/libgobject-2.0.0.dylib", "/usr/local/lib/libgobject-2.0.0.dylib")
        val gioName: String? = load("libgio-2.0.so.0", "/opt/homebrew/lib/libgio-2.0.0.dylib", "/usr/local/lib/libgio-2.0.0.dylib")
        if (glibName == null || gobjectName == null || gioName == null) {
            check(System.getProperty("kmptoolkit.secretstore") != "linux") { "kmptoolkit.secretstore=linux: GLib, GObject and GIO must load on this host" }
            Assume.assumeTrue("needs GLib, GObject and GIO (a Linux host, or Homebrew glib on a Mac)", false)
            return
        }
        val glib: GLibApi = Native.load(glibName, GLibApi::class.java)
        val gobject: GObjectApi = Native.load(gobjectName, GObjectApi::class.java)
        val extras: GObjectExtras = Native.load(gobjectName, GObjectExtras::class.java)
        val tasks: GioTaskApi = Native.load(gioName, GioTaskApi::class.java)

        val finalisedTasks = AtomicInteger()
        val finalised = object : WeakNotify {
            override fun invoke(data: Pointer?, where: Pointer?) {
                finalisedTasks.incrementAndGet()
            }
        }
        val answered = AtomicInteger()
        val finished = AtomicInteger()
        val answer: Pointer = checkNotNull(extras.g_object_new(G_TYPE_OBJECT, null)) { "g_object_new(GObject) failed" }

        // Only libsecret is replaced; everything else of the Linux libraries is the real GLib and GObject.
        val secret: SecretApi = object : SecretApi by FakeLinuxLibraries().secret {
            override fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?) {
                answered.incrementAndGet()
                val task: Pointer = checkNotNull(tasks.g_task_new(null, cancellable, callback, userData)) { "g_task_new failed" }
                extras.g_object_weak_ref(task, finalised, null)
                tasks.g_task_return_pointer(task, answer, null)
                // libsecret drops its own reference right after returning the task; the callback's reference keeps it alive.
                gobject.g_object_unref(task)
            }

            override fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer? {
                finished.incrementAndGet()
                return tasks.g_task_propagate_pointer(result, error)
            }
        }
        val gio: GioApi = Native.load(gioName, GioApi::class.java)
        val libraries = LinuxLibraries(glib, gobject, gio, secret, hasBackendApi = true)
        val calls = JnaSecretServiceCalls(lazyOf(LinuxPlatform.Ready(libraries)))

        val outcome = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                val result: RawResult<Boolean> = calls.backendIsPortal()
                // A GObject is not the file backend: the verdict is "not the portal", which is what a plain libsecret says.
                assertEquals(false, assertIs<RawResult.Success<Boolean>>(result).value)
                // The thread-default context was popped: a new push and pop pair works on this thread.
                val context: Pointer = checkNotNull(glib.g_main_context_new())
                glib.g_main_context_push_thread_default(context)
                glib.g_main_context_pop_thread_default(context)
                glib.g_main_context_unref(context)
            } catch (failure: Throwable) {
                outcome.set(failure)
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(TimeUnit.SECONDS.toMillis(30))
        assertTrue(!worker.isAlive, "the answer never reached the callback: it was delivered to a context nobody iterates (the private context was not the thread default)")
        outcome.get()?.let { throw AssertionError("the probe failed on the worker thread", it) }
        assertEquals(1, answered.get(), "control: the backend question was asked once")
        assertEquals(1, finished.get(), "the answer was read once")
        assertEquals(1, finalisedTasks.get(), "the GAsyncResult (the GTask) must be released by the probe: its reference is the last one")
        gobject.g_object_unref(answer)
    }

    private companion object {
        /** `G_TYPE_OBJECT`: `G_TYPE_MAKE_FUNDAMENTAL(20)`. */
        const val G_TYPE_OBJECT: Long = 80L
    }
}
