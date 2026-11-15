package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretStatus

/** The GLib functions the Secret Service calls need; every name is the C name. */
internal interface GLibApi : Library {
    fun g_error_free(error: Pointer)
    fun g_quark_to_string(quark: Int): Pointer?
    fun g_free(memory: Pointer?)
    fun g_list_free(list: Pointer?)

    /** `GMainContext *g_main_context_new(void)`: a private context that delivers the answer of one asynchronous libsecret call. */
    fun g_main_context_new(): Pointer?
    fun g_main_context_push_thread_default(context: Pointer)
    fun g_main_context_pop_thread_default(context: Pointer)

    /** `gboolean g_main_context_iteration(GMainContext *context, gboolean may_block)`; JNA's `Boolean` is a 4-byte int, as a `gboolean` is. */
    fun g_main_context_iteration(context: Pointer, mayBlock: Boolean): Boolean
    fun g_main_context_unref(context: Pointer)
}

/** The GObject functions the Secret Service calls need; every name is the C name. */
internal interface GObjectApi : Library {
    fun g_object_unref(instance: Pointer)

    /** `gpointer g_object_ref(gpointer object)`: the extra reference a callback takes on the `GAsyncResult` it is handed. */
    fun g_object_ref(instance: Pointer): Pointer?
    fun g_type_check_instance_is_a(instance: Pointer, type: Long): Boolean

    /** `const gchar *g_type_name_from_instance(GTypeInstance *instance)`: a static string, never freed. */
    fun g_type_name_from_instance(instance: Pointer): Pointer?
}

/**
 * A `GAsyncReadyCallback`: `void (*)(GObject *source_object, GAsyncResult *res, gpointer user_data)`. JNA turns an
 * implementation into a native function pointer; the object must stay strongly reachable until libsecret can no longer
 * call it.
 */
internal fun interface GAsyncReadyCallback : Callback {
    fun invoke(source: Pointer?, result: Pointer?, userData: Pointer?)
}

/** The one gio function the Secret Service calls need. */
internal interface GioApi : Library {
    /** The D-Bus error name of a remote error (a new string for `g_free`), or `NULL` for a local one. */
    fun g_dbus_error_get_remote_error(error: Pointer): Pointer?
}

/**
 * The libsecret functions; every name is the C name. The `secret_password_*` functions are variadic: the attribute
 * name and value pairs follow the fixed arguments and end with a `NULL` pointer (JNA passes a `vararg` correctly on every
 * supported ABI).
 */
internal interface SecretApi : Library {
    fun secret_password_search_sync(schema: Pointer, flags: Int, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Pointer?
    fun secret_password_store_sync(
        schema: Pointer,
        collection: Pointer,
        label: Pointer,
        password: Pointer,
        cancellable: Pointer?,
        error: PointerByReference,
        vararg attributes: Pointer?,
    ): Boolean

    fun secret_password_clear_sync(schema: Pointer, cancellable: Pointer?, error: PointerByReference, vararg attributes: Pointer?): Boolean
    fun secret_retrievable_retrieve_secret_sync(retrievable: Pointer, cancellable: Pointer?, error: PointerByReference): Pointer?
    fun secret_value_get(value: Pointer, length: Pointer): Pointer?
    fun secret_value_unref(value: Pointer)
    fun secret_item_get_type(): Long
    fun secret_item_get_locked(item: Pointer): Boolean
    fun secret_service_get_sync(flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
    fun secret_collection_for_alias_sync(service: Pointer, alias: Pointer, flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
    fun secret_collection_get_locked(collection: Pointer): Boolean

    /**
     * `void secret_backend_get(SecretBackendFlags flags, GCancellable *cancellable, GAsyncReadyCallback callback, gpointer user_data)`
     * (libsecret 0.20 or newer, `secret-backend.h`; there is no `_sync` variant). The callback runs inside a
     * `g_main_context_iteration` of the thread-default context that was current when this was called.
     */
    fun secret_backend_get(flags: Int, cancellable: Pointer?, callback: GAsyncReadyCallback, userData: Pointer?)

    /** `SecretBackend *secret_backend_get_finish(GAsyncResult *result, GError **error)`; see [JnaSecretServiceCalls.backendIsPortal] for who owns the answer. */
    fun secret_backend_get_finish(result: Pointer, error: PointerByReference): Pointer?
}

/** Every Linux library this binding talks to; what [LinuxPlatform.load] could not load is reported as a [RawStatus.SYMBOL_MISSING]. */
internal class LinuxLibraries(
    val glib: GLibApi,
    val gobject: GObjectApi,
    val gio: GioApi,
    val secret: SecretApi,
    /**
     * Whether libsecret exports `secret_backend_get` and `secret_backend_get_finish` (0.20 or newer). Older versions have
     * no file or portal backend, so asking them which backend is selected is answered `false` without a native call.
     */
    val hasBackendApi: Boolean = true,
)

/** What loading libsecret found: the libraries, or the fact that this machine has no usable libsecret. */
internal sealed interface LinuxPlatform {

    /** libsecret 0.19 or newer is loaded and every symbol this binding uses resolved. */
    class Ready(val libraries: LinuxLibraries) : LinuxPlatform

    /** A library or a required symbol is missing (for example RHEL 8's libsecret 0.18.6); no exception is involved. */
    data object SymbolMissing : LinuxPlatform

    companion object {
        const val LIBSECRET: String = "libsecret-1.so.0"
        const val GLIB: String = "libglib-2.0.so.0"
        const val GOBJECT: String = "libgobject-2.0.so.0"
        const val GIO: String = "libgio-2.0.so.0"

        /** `secret_password_search_sync` first: its absence is what identifies libsecret older than 0.19. */
        val REQUIRED_SYMBOLS: List<String> = listOf(
            "secret_password_search_sync",
            "secret_password_store_sync",
            "secret_password_clear_sync",
            "secret_retrievable_retrieve_secret_sync",
            "secret_value_get",
            "secret_value_unref",
            "secret_item_get_type",
            "secret_item_get_locked",
            "secret_service_get_sync",
            "secret_collection_for_alias_sync",
            "secret_collection_get_locked",
        )

        /** Present from libsecret 0.20; their absence is not an error (there is no portal backend then), see [LinuxLibraries.hasBackendApi]. */
        val BACKEND_SYMBOLS: List<String> = listOf("secret_backend_get", "secret_backend_get_finish")

        /**
         * Loads the libraries by soname, through the system loader. JNA's own native library is forced first and its
         * failure propagates as a [LinkageError] (that is `NATIVE_ACCESS_UNAVAILABLE`). A missing libsecret, glib, gobject
         * or gio, or a missing symbol, is [SymbolMissing]: the one case where an [UnsatisfiedLinkError] is caught, because it
         * means "no key store here", never "native access is blocked".
         */
        fun load(): LinuxPlatform {
            Native.POINTER_SIZE
            try {
                val secret: NativeLibrary = NativeLibrary.getInstance(LIBSECRET)
                for (symbol: String in REQUIRED_SYMBOLS) secret.getFunction(symbol)
                val hasBackendApi: Boolean = BACKEND_SYMBOLS.all { symbol: String -> secret.hasFunction(symbol) }
                return Ready(
                    LinuxLibraries(
                        glib = Native.load(GLIB, GLibApi::class.java),
                        gobject = Native.load(GOBJECT, GObjectApi::class.java),
                        gio = Native.load(GIO, GioApi::class.java),
                        secret = Native.load(LIBSECRET, SecretApi::class.java),
                        hasBackendApi = hasBackendApi,
                    ),
                )
            } catch (_: UnsatisfiedLinkError) {
                return SymbolMissing
            }
        }
    }
}

private fun NativeLibrary.hasFunction(name: String): Boolean = try {
    getFunction(name)
    true
} catch (_: UnsatisfiedLinkError) {
    false
}

/**
 * The Linux binding: the freedesktop Secret Service through libsecret's own backend selection (so the Flatpak portal and
 * file backends keep working), with the schema `io.github.jamal_wia.kmptoolkit.storage.Key`.
 *
 * - Every `GError`, `GList` of results, `SecretValue`, `GObject` reference and `g_dbus_error_get_remote_error` string
 *   a call obtains is freed in a `finally`, the failure paths included; the native copy of a secret that is stored is zeroed and
 *   freed.
 * - A [LinuxPlatform.SymbolMissing] platform answers every call with [RawStatus.SYMBOL_MISSING] (no exception).
 * - [defaultCollectionLocked] treats an absent default collection as locked: using it needs the service to create one,
 *   which can show a prompt. On libsecret's file (portal) backend it answers "unlocked" without a D-Bus call.
 * - [backendIsPortal] asks libsecret which backend it selected, through `secret_backend_get` on a private main context.
 *
 * Calls arrive one at a time from the native-executor thread; this class holds no state between calls.
 */
internal class JnaSecretServiceCalls(
    private val loaded: Lazy<LinuxPlatform>,
    private val scratch: ScratchMemory = ScratchMemory.Default,
) : SecretServiceCalls {

    /** The libraries, loaded by the first call (never by constructing this object). */
    private val platform: LinuxPlatform get() = loaded.value

    override fun lookup(attributes: Map<String, String>, allowUi: Boolean): RawResult<SecretLookup> {
        val libs: LinuxLibraries = (platform as? LinuxPlatform.Ready)?.libraries
            ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        return Scope(libs).use { scope: Scope ->
            val schema: Pointer = scope.schema(attributes.keys)
            val flags: Int = SEARCH_ALL or (if (allowUi) SEARCH_UNLOCK else 0)
            val error = PointerByReference()
            val list: Pointer? = libs.secret.secret_password_search_sync(schema, flags, null, error, *scope.pairs(attributes))
            scope.takeError(error)?.let { return@use RawResult.Failure(it) }
            if (list == null) return@use RawResult.Success(SecretLookup.NotFound)
            val nodes: ArrayList<Pointer> = ArrayList()
            var node: Pointer? = list
            while (node != null) {
                node.getPointer(0L)?.let { nodes.add(it) }
                node = node.getPointer(Native.POINTER_SIZE.toLong())
            }
            try {
                resolve(libs, scope, nodes)
            } finally {
                for (retrievable: Pointer in nodes) libs.gobject.g_object_unref(retrievable)
                libs.glib.g_list_free(list)
            }
        }
    }

    /** The first unlocked match's secret; else a locked match; else nothing matched. */
    private fun resolve(libs: LinuxLibraries, scope: Scope, retrievables: List<Pointer>): RawResult<SecretLookup> {
        if (retrievables.isEmpty()) return RawResult.Success(SecretLookup.NotFound)
        val itemType: Long = libs.secret.secret_item_get_type()
        var lockedMatch = false
        for (retrievable: Pointer in retrievables) {
            val isItem: Boolean = libs.gobject.g_type_check_instance_is_a(retrievable, itemType)
            if (isItem && libs.secret.secret_item_get_locked(retrievable)) {
                lockedMatch = true
                continue
            }
            val error = PointerByReference()
            val value: Pointer? = libs.secret.secret_retrievable_retrieve_secret_sync(retrievable, null, error)
            scope.takeError(error)?.let { return RawResult.Failure(it) }
            if (value == null) return RawResult.Failure(RawStatus(NO_SECRET_CODE, NO_SECRET_DOMAIN))
            try {
                return RawResult.Success(SecretLookup.Found(copySecret(libs, scope, value)))
            } finally {
                libs.secret.secret_value_unref(value)
            }
        }
        return RawResult.Success(if (lockedMatch) SecretLookup.LockedMatch else SecretLookup.NotFound)
    }

    private fun copySecret(libs: LinuxLibraries, scope: Scope, value: Pointer): ByteArray {
        val length: Memory = scope.native(Native.SIZE_T_SIZE.toLong())
        val bytes: Pointer? = libs.secret.secret_value_get(value, length)
        val size: Long = length.getNativeLong(0L).toLong()
        return if (bytes == null || size <= 0L) ByteArray(0) else bytes.getByteArray(0L, size.toInt())
    }

    override fun store(label: String, attributes: Map<String, String>, secret: ByteArray): SecretStatus {
        val libs: LinuxLibraries = (platform as? LinuxPlatform.Ready)?.libraries ?: return RawStatus.SYMBOL_MISSING
        return Scope(libs).use { scope: Scope ->
            scratch.withCopy(secret, terminator = 1) { password: Memory ->
                val error = PointerByReference()
                val stored: Boolean = libs.secret.secret_password_store_sync(
                    scope.schema(attributes.keys),
                    scope.cString(COLLECTION_DEFAULT),
                    scope.cString(label),
                    password,
                    null,
                    error,
                    *scope.pairs(attributes),
                )
                // FALSE with no GError is libsecret refusing the call (its attribute validation, "warnings raised already"):
                // nothing was stored, so it is a failure, the same raw status the FFM binding reports.
                scope.takeError(error) ?: if (stored) RawStatus.SUCCESS else RawStatus(G_IO_ERROR_FAILED, G_IO_ERROR_DOMAIN)
            }
        }
    }

    override fun clear(attributes: Map<String, String>): SecretStatus {
        val libs: LinuxLibraries = (platform as? LinuxPlatform.Ready)?.libraries ?: return RawStatus.SYMBOL_MISSING
        return Scope(libs).use { scope: Scope ->
            val error = PointerByReference()
            // The boolean says whether anything was removed; an empty match is a success, only an error is a failure.
            libs.secret.secret_password_clear_sync(scope.schema(attributes.keys), null, error, *scope.pairs(attributes))
            scope.takeError(error) ?: RawStatus.SUCCESS
        }
    }

    override fun defaultCollectionLocked(): RawResult<Boolean> {
        val libs: LinuxLibraries = (platform as? LinuxPlatform.Ready)?.libraries
            ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        return Scope(libs).use { scope: Scope ->
            // On libsecret's file (portal) backend there is no D-Bus Secret Service collection to read and its items are never
            // locked: answer "unlocked" without touching the session bus, as lookup and store (which go through libsecret's
            // own backend selection) work there. A failure to select a backend is this call's failure, with its own status.
            when (val portal: RawResult<Boolean> = selectedBackendIsFileBackend(libs, scope)) {
                is RawResult.Failure -> return@use portal
                is RawResult.Success -> if (portal.value) return@use RawResult.Success(false)
            }
            val serviceError = PointerByReference()
            val service: Pointer? = libs.secret.secret_service_get_sync(SERVICE_NONE, null, serviceError)
            scope.takeError(serviceError)?.let { return@use RawResult.Failure(it) }
            if (service == null) return@use RawResult.Failure(RawStatus(NO_SECRET_CODE, NO_SECRET_DOMAIN))
            try {
                val collectionError = PointerByReference()
                val collection: Pointer? =
                    libs.secret.secret_collection_for_alias_sync(service, scope.cString(DEFAULT_ALIAS), COLLECTION_NONE, null, collectionError)
                scope.takeError(collectionError)?.let { return@use RawResult.Failure(it) }
                if (collection == null) return@use RawResult.Success(true)
                try {
                    RawResult.Success(libs.secret.secret_collection_get_locked(collection))
                } finally {
                    libs.gobject.g_object_unref(collection)
                }
            } finally {
                libs.gobject.g_object_unref(service)
            }
        }
    }

    override fun backendIsPortal(): RawResult<Boolean> {
        val libs: LinuxLibraries = (platform as? LinuxPlatform.Ready)?.libraries
            ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        return Scope(libs).use { scope: Scope -> selectedBackendIsFileBackend(libs, scope) }
    }

    /**
     * Asks libsecret which backend it selected (`secret_backend_get`, which has no synchronous variant) and answers whether it
     * is the file backend, the one libsecret uses for the Flatpak Secret portal. This is the sequence libsecret's own
     * `secret_password_*_sync` functions use: a private `GMainContext` is pushed as the thread default so the `GTask`
     * delivers its answer only into this thread's iteration, never into a loop another component runs; the context is
     * popped, drained and released in `finally`, in that order.
     *
     * - A libsecret without `secret_backend_get` (older than 0.20, [LinuxLibraries.hasBackendApi]) has no portal backend:
     *   `Success(false)` with no native call.
     * - The `SecretBackend` that `secret_backend_get_finish` returns is deliberately **not** unreferenced. libsecret's
     *   `secret_backend_get_finish` stores a newly created backend in its process-wide cache and returns the same pointer
     *   without a reference of its own (secret-backend.c, `backend_instance`, libsecret 0.20.0 to 0.21.8 and main), so the
     *   caller's reference and the cache's are one: releasing it frees the singleton under libsecret, and the next
     *   `secret_password_*_sync` uses freed memory. libsecret's own callers never release it either. The price is at most
     *   one reference on a process-lifetime singleton per call.
     * - Like every other `*_sync` call this waits for libsecret to answer; the time limit and the abandoning of a stuck
     *   thread belong to the native executor above the seam.
     */
    private fun selectedBackendIsFileBackend(libs: LinuxLibraries, scope: Scope): RawResult<Boolean> {
        if (!libs.hasBackendApi) return RawResult.Success(false)
        val catcher = AsyncResultCatcher(libs.gobject)
        val context: Pointer = libs.glib.g_main_context_new() ?: return RawResult.Failure(RawStatus(NO_SECRET_CODE, NO_SECRET_DOMAIN))
        var pushed = false
        val error = PointerByReference()
        var backend: Pointer? = null
        var failure: RawStatus? = null
        try {
            libs.glib.g_main_context_push_thread_default(context)
            pushed = true
            libs.secret.secret_backend_get(BACKEND_FLAGS_NONE, null, catcher, null)
            // Checked before the first iteration too, so an answer that is already there is never waited for.
            while (!catcher.hasResult) libs.glib.g_main_context_iteration(context, true)
            backend = libs.secret.secret_backend_get_finish(catcher.result!!, error)
            failure = scope.takeError(error)
        } finally {
            // 1. pop on the thread that pushed, always; 2. run what is already pending (never a wait); 3. drop our reference on
            // the result, which frees the GTask; 4. drop the context; 5. only now may the callback trampoline be collected.
            // Each step runs even when an earlier one throws.
            try {
                if (pushed) libs.glib.g_main_context_pop_thread_default(context)
            } finally {
                try {
                    while (libs.glib.g_main_context_iteration(context, false)) {
                        // dispatched one pending source
                    }
                } finally {
                    try {
                        catcher.release()
                    } finally {
                        try {
                            libs.glib.g_main_context_unref(context)
                        } finally {
                            Reference.reachabilityFence(catcher)
                        }
                    }
                }
            }
        }
        if (failure != null) return RawResult.Failure(failure)
        if (backend == null) return RawResult.Failure(RawStatus(NO_SECRET_CODE, NO_SECRET_DOMAIN))
        val name: String? = libs.gobject.g_type_name_from_instance(backend)?.getString(0L)
        return RawResult.Success(name == FILE_BACKEND_TYPE)
    }

    /**
     * The `GAsyncReadyCallback` of [selectedBackendIsFileBackend]. libsecret hands the `GAsyncResult` to the callback
     * borrowed, so the callback takes its own reference, which [release] returns. The callback does nothing else and never
     * throws; it runs on the thread that is iterating the private context.
     */
    private class AsyncResultCatcher(private val gobject: GObjectApi) : GAsyncReadyCallback {
        private var held: Pointer? = null

        val hasResult: Boolean get() = held != null
        val result: Pointer? get() = held

        override fun invoke(source: Pointer?, result: Pointer?, userData: Pointer?) {
            if (held == null && result != null) held = gobject.g_object_ref(result)
        }

        fun release() {
            val result: Pointer = held ?: return
            held = null
            gobject.g_object_unref(result)
        }
    }

    /** The native blocks and error objects of one call; [close] frees them all. */
    private inner class Scope(private val libs: LinuxLibraries) : AutoCloseable {
        private val blocks: ArrayList<Memory> = ArrayList()

        fun native(size: Long): Memory {
            val block: Memory = scratch.allocate(size)
            block.clear()
            blocks.add(block)
            return block
        }

        fun cString(text: String): Memory {
            val bytes: ByteArray = text.utf8()
            val block: Memory = native(bytes.size.toLong() + 1L)
            block.write(0L, bytes, 0, bytes.size)
            return block
        }

        /** The `name, value, ..., NULL` tail of a variadic call. */
        fun pairs(attributes: Map<String, String>): Array<Pointer?> {
            val tail: ArrayList<Pointer?> = ArrayList(attributes.size * 2 + 1)
            for ((name: String, value: String) in attributes) {
                tail.add(cString(name))
                tail.add(cString(value))
            }
            tail.add(null)
            return tail.toTypedArray()
        }

        /** A `SecretSchema` named [SCHEMA_NAME] whose attributes are all strings, one per [names] entry. */
        fun schema(names: Set<String>): Pointer {
            require(names.size <= MAX_SCHEMA_ATTRIBUTES) { "A schema holds at most $MAX_SCHEMA_ATTRIBUTES attributes" }
            val schema: Memory = native(SCHEMA_SIZE)
            schema.setPointer(0L, cString(SCHEMA_NAME))
            schema.setInt(Native.POINTER_SIZE.toLong(), SCHEMA_FLAGS_NONE)
            var offset: Long = SCHEMA_ATTRIBUTES_OFFSET
            for (name: String in names) {
                schema.setPointer(offset, cString(name))
                schema.setInt(offset + Native.POINTER_SIZE, ATTRIBUTE_TYPE_STRING)
                offset += ATTRIBUTE_SIZE
            }
            return schema
        }

        /** Converts a `GError**` into a status and frees the error; `null` when no error was set. */
        fun takeError(slot: PointerByReference): RawStatus? {
            val error: Pointer = slot.value ?: return null
            try {
                val domainQuark: Int = error.getInt(0L)
                val code: Int = error.getInt(4L)
                val domain: String? = libs.glib.g_quark_to_string(domainQuark)?.getString(0L)
                val remote: Pointer? = libs.gio.g_dbus_error_get_remote_error(error)
                val errorName: String? = try {
                    remote?.getString(0L)
                } finally {
                    if (remote != null) libs.glib.g_free(remote)
                }
                return RawStatus(if (code == 0) GENERIC_ERROR_CODE else code, domain ?: GENERIC_DOMAIN, errorName)
            } finally {
                libs.glib.g_error_free(error)
            }
        }

        override fun close() {
            for (block: Memory in blocks) {
                block.clear()
                block.close()
            }
            blocks.clear()
        }
    }

    internal companion object {
        const val SCHEMA_NAME: String = "io.github.jamal_wia.kmptoolkit.storage.Key"
        const val COLLECTION_DEFAULT: String = "default"
        const val DEFAULT_ALIAS: String = "default"

        /** The registered GType name of libsecret's file backend, the one it selects for the Flatpak Secret portal. */
        const val FILE_BACKEND_TYPE: String = "SecretFileBackend"

        /** `SECRET_BACKEND_NONE`. */
        const val BACKEND_FLAGS_NONE: Int = 0

        /** `SECRET_SEARCH_ALL` and `SECRET_SEARCH_UNLOCK`. */
        const val SEARCH_ALL: Int = 2
        const val SEARCH_UNLOCK: Int = 4
        const val SERVICE_NONE: Int = 0
        const val COLLECTION_NONE: Int = 0
        const val SCHEMA_FLAGS_NONE: Int = 0
        const val ATTRIBUTE_TYPE_STRING: Int = 0
        const val MAX_SCHEMA_ATTRIBUTES: Int = 32

        /** `struct SecretSchema`: name, flags, 32 attributes of (name, type), then the private reserved fields. */
        // Computed on use, never at class-load time: reading Native.POINTER_SIZE loads JNA's native library, and loading
        // this class must not (the binding is constructed by the selector before any call).
        private val ATTRIBUTE_SIZE: Long get() = 2L * Native.POINTER_SIZE
        private val SCHEMA_ATTRIBUTES_OFFSET: Long get() = 2L * Native.POINTER_SIZE
        val SCHEMA_SIZE: Long get() = SCHEMA_ATTRIBUTES_OFFSET + MAX_SCHEMA_ATTRIBUTES * ATTRIBUTE_SIZE + 8L * Native.POINTER_SIZE

        /** A `GError` code of 0 or a missing domain would read as success or as nothing: a failure always carries both. */
        const val GENERIC_ERROR_CODE: Int = -1
        const val GENERIC_DOMAIN: String = "kmptoolkit-binding"
        const val NO_SECRET_CODE: Int = -2
        const val NO_SECRET_DOMAIN: String = "kmptoolkit-binding"

        /** What libsecret's `secret_password_store_sync` answering FALSE without a `GError` is reported as: `G_IO_ERROR_FAILED` of the gio domain. */
        const val G_IO_ERROR_FAILED: Int = 0
        const val G_IO_ERROR_DOMAIN: String = "g-io-error-quark"
    }
}
