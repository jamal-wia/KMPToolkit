package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
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
}

/** The GObject functions the Secret Service calls need; every name is the C name. */
internal interface GObjectApi : Library {
    fun g_object_unref(instance: Pointer)
    fun g_type_check_instance_is_a(instance: Pointer, type: Long): Boolean
    fun g_type_name(type: Long): Pointer?
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
    fun secret_backend_get_sync(flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
}

/** Every Linux library this binding talks to; what [LinuxPlatform.load] could not load is reported as a [RawStatus.SYMBOL_MISSING]. */
internal class LinuxLibraries(
    val glib: GLibApi,
    val gobject: GObjectApi,
    val gio: GioApi,
    val secret: SecretApi,
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
                return Ready(
                    LinuxLibraries(
                        glib = Native.load(GLIB, GLibApi::class.java),
                        gobject = Native.load(GOBJECT, GObjectApi::class.java),
                        gio = Native.load(GIO, GioApi::class.java),
                        secret = Native.load(LIBSECRET, SecretApi::class.java),
                    ),
                )
            } catch (_: UnsatisfiedLinkError) {
                return SymbolMissing
            }
        }
    }
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
 *   which can show a prompt.
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
                libs.secret.secret_password_store_sync(
                    scope.schema(attributes.keys),
                    scope.cString(COLLECTION_DEFAULT),
                    scope.cString(label),
                    password,
                    null,
                    error,
                    *scope.pairs(attributes),
                )
                scope.takeError(error) ?: RawStatus.SUCCESS
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
        return Scope(libs).use { scope: Scope ->
            val error = PointerByReference()
            val backend: Pointer? = try {
                libs.secret.secret_backend_get_sync(0, null, error)
            } catch (_: UnsatisfiedLinkError) {
                // secret_backend_get_sync is optional: without it libsecret has no portal backend to select.
                return@use RawResult.Success(false)
            }
            scope.takeError(error)?.let { return@use RawResult.Failure(it) }
            if (backend == null) return@use RawResult.Success(false)
            try {
                val type: Long = backend.getPointer(0L)?.getNativeLong(0L)?.toLong() ?: 0L
                val name: String? = if (type == 0L) null else libs.gobject.g_type_name(type)?.getString(0L)
                RawResult.Success(name == FILE_BACKEND_TYPE)
            } finally {
                libs.gobject.g_object_unref(backend)
            }
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
        const val FILE_BACKEND_TYPE: String = "SecretFileBackend"

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
    }
}
