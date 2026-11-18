package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretLookup
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretStatus
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * The Linux half of the FFM binding: libsecret (`libsecret-1.so.0`, resolved by the system loader) over glib and gio.
 *
 * - **Lookup goes through libsecret's backend selection** (`secret_password_searchv_sync`), so the Flatpak portal and file
 *   backends keep working; the Secret Service D-Bus API is never used for lookup. The flags are `SECRET_SEARCH_ALL`, plus
 *   `SECRET_SEARCH_UNLOCK` when the caller allows UI. The `v` form takes a `GHashTable`, which keeps every call a fixed
 *   non-variadic signature.
 * - **Binding load never throws for a missing library or symbol.** If `libsecret-1.so.0` cannot be opened, or any symbol the
 *   binding needs is absent (libsecret older than 0.19, such as RHEL 8's 0.18.6, lacks `secret_password_search_sync` and
 *   `secret_password_searchv_sync`), every call answers [RawStatus.SYMBOL_MISSING]: `SymbolLookup.find` returns empty and is
 *   never `orElseThrow`n. Above the seam that is `Unsupported(NO_KEY_STORE)`. libsecret 0.19.x is supported: it exports no
 *   `secret_backend_get` (that pair is exported since 0.20.1), and without it [backendIsPortal] answers `Success(false)`, because
 *   a libsecret that old has no portal backend.
 * - **The backend question uses libsecret's asynchronous pair**, the only API there is (`secret_backend_get` and
 *   `secret_backend_get_finish`; there is no `_sync`). It is run the way libsecret runs its own `_sync` functions: a private
 *   `GMainContext` is pushed as the thread default, `secret_backend_get` is started, and the context is iterated on the calling
 *   (native-executor) thread until the callback has fired; the callback calls `secret_backend_get_finish` and records the answer.
 *   The wait is unbounded here, as in libsecret's own `_sync` calls; the native executor bounds it from outside.
 *   The returned `SecretBackend` is **borrowed, never unreferenced**: when this is the first backend request of the process
 *   libsecret keeps the one reference it returns as its process-wide singleton, so releasing it would free the singleton.
 *   (libsecret's own callers do the same.) It costs one reference-count increment per call on that singleton.
 * - **A missing default collection counts as locked**: [defaultCollectionLocked] answers `true` when no
 *   collection exists at the `default` alias (storing would have to create one, which prompts like an unlock), `false` on the
 *   portal/file backend (it has no locked collection), and the collection's `Locked` property otherwise.
 * - **Every OS-allocated output is owned by the call's [NativeScope]** right after it is returned: the `GError` (`g_error_free`),
 *   D-Bus error name strings (`g_free`), the `GHashTable` (`g_hash_table_unref`), the result `GList` and its `GObject`
 *   references, the `SecretValue` (`secret_value_unref`, which libsecret wipes), the `SecretCollection`
 *   reference, and the private `GMainContext` of the backend question (its thread-default push is popped first).
 *   The native copy of the secret that this layer makes (the password argument of a store) is wiped on close.
 * - **A returned object counts as a locked match only if it is a `SecretItem` whose `secret_item_get_locked()` is true**
 *   (file-backend items are never locked): that is [SecretLookup.LockedMatch], never "not found".
 *
 * The secret stored and returned is the base64 text of the data key, as bytes; it must not contain NUL.
 */
internal class FfmSecretService(
    private val foreign: Foreign,
    private val ledger: NativeLedger,
) : SecretServiceCalls {

    /** The resolved libsecret, or `null` when the library or one of its symbols is missing (every call then answers SYMBOL_MISSING). */
    private val api: Api? = Api.load(foreign)

    override fun lookup(attributes: Map<String, String>, allowUi: Boolean): RawResult<SecretLookup> {
        val api: Api = api ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        return ledger.scope().use { scope: NativeScope ->
            val flags: Int = SECRET_SEARCH_ALL or if (allowUi) SECRET_SEARCH_UNLOCK else 0
            val error: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            val list: MemorySegment = api.searchv.pointer(schema(scope, attributes), table(scope, api, attributes), flags, MemorySegment.NULL, error)
            // The list and its references are ours whether or not an error came back; own them before reading anything.
            if (list.address() != 0L) scope.own(list) { head: MemorySegment -> api.freeList(head) }
            if (error.get(ValueLayout.ADDRESS, 0L).address() != 0L) return@use RawResult.Failure(api.takeError(scope, error))
            if (list.address() == 0L) return@use RawResult.Success(SecretLookup.NotFound)
            var unlocked: MemorySegment? = null
            var locked = false
            var node: MemorySegment = list
            while (node.address() != 0L) {
                val cell: MemorySegment = node.reinterpret(GLIST_SIZE)
                val retrievable: MemorySegment = cell.get(ValueLayout.ADDRESS, 0L)
                if (api.isLockedItem(retrievable)) locked = true else if (unlocked == null) unlocked = retrievable
                node = cell.get(ValueLayout.ADDRESS, GLIST_NEXT_OFFSET)
            }
            val chosen: MemorySegment = unlocked ?: return@use RawResult.Success(if (locked) SecretLookup.LockedMatch else SecretLookup.NotFound)
            val retrieveError: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            val value: MemorySegment = api.retrieve.pointer(chosen, MemorySegment.NULL, retrieveError)
            if (value.address() != 0L) scope.own(value) { held: MemorySegment -> api.valueUnref.invoke(held) }
            if (retrieveError.get(ValueLayout.ADDRESS, 0L).address() != 0L) {
                return@use RawResult.Failure(api.takeError(scope, retrieveError))
            }
            if (value.address() == 0L) return@use RawResult.Failure(RawStatus(G_IO_ERROR_FAILED, G_IO_ERROR_DOMAIN))
            val length: MemorySegment = scope.arena.allocate(ValueLayout.JAVA_LONG)
            val text: MemorySegment = api.valueGet.pointer(value, length)
            val size: Long = length.get(ValueLayout.JAVA_LONG, 0L)
            if (text.address() == 0L || size < 0L || size > MAX_SECRET_BYTES) {
                return@use RawResult.Failure(RawStatus(G_IO_ERROR_FAILED, G_IO_ERROR_DOMAIN))
            }
            RawResult.Success(SecretLookup.Found(if (size == 0L) ByteArray(0) else text.reinterpret(size).toArray(ValueLayout.JAVA_BYTE)))
        }
    }

    override fun store(label: String, attributes: Map<String, String>, secret: ByteArray): SecretStatus {
        val api: Api = api ?: return RawStatus.SYMBOL_MISSING
        require(secret.none { it == 0.toByte() }) { "A libsecret password is a C string and cannot contain NUL" }
        return ledger.scope().use { scope: NativeScope ->
            val error: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            // The password is a NUL-terminated C string; this is the native copy of the key text and it is wiped on close.
            val password: MemorySegment = scope.secret(secret.size + 1L)
            MemorySegment.copy(secret, 0, password, ValueLayout.JAVA_BYTE, 0L, secret.size)
            val stored: Int = api.store.int(
                schema(scope, attributes), table(scope, api, attributes), cString(scope, COLLECTION_DEFAULT),
                cString(scope, label), password, MemorySegment.NULL, error,
            )
            if (error.get(ValueLayout.ADDRESS, 0L).address() != 0L) return@use api.takeError(scope, error)
            if (stored == 0) RawStatus(G_IO_ERROR_FAILED, G_IO_ERROR_DOMAIN) else RawStatus.SUCCESS
        }
    }

    override fun clear(attributes: Map<String, String>): SecretStatus {
        val api: Api = api ?: return RawStatus.SYMBOL_MISSING
        return ledger.scope().use { scope: NativeScope ->
            val error: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            // The result is TRUE when an item was removed and FALSE for "nothing matched" or an error: only the error matters here.
            api.clear.int(schema(scope, attributes), table(scope, api, attributes), MemorySegment.NULL, error)
            if (error.get(ValueLayout.ADDRESS, 0L).address() != 0L) api.takeError(scope, error) else RawStatus.SUCCESS
        }
    }

    override fun defaultCollectionLocked(): RawResult<Boolean> {
        val api: Api = api ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        // The backend question runs in a scope of its own: its private main context is popped again before the collection
        // query below makes its own libsecret calls. The portal and file backends have no locked collection.
        val portal: RawResult<Boolean> = backendIsPortal(api)
        if (portal is RawResult.Failure) return portal
        if ((portal as RawResult.Success<Boolean>).value) return RawResult.Success(false)
        return ledger.scope().use { scope: NativeScope ->
            val error: MemorySegment = scope.arena.allocate(ValueLayout.ADDRESS)
            val collection: MemorySegment =
                api.collectionForAlias.pointer(MemorySegment.NULL, cString(scope, COLLECTION_DEFAULT), 0, MemorySegment.NULL, error)
            if (collection.address() != 0L) scope.own(collection) { held: MemorySegment -> api.objectUnref.invoke(held) }
            if (error.get(ValueLayout.ADDRESS, 0L).address() != 0L) return@use RawResult.Failure(api.takeError(scope, error))
            // No default collection at all: a store would have to create one, which prompts exactly like an unlock (seam contract).
            if (collection.address() == 0L) return@use RawResult.Success(true)
            RawResult.Success(api.collectionGetLocked.int(collection) != 0)
        }
    }

    override fun backendIsPortal(): RawResult<Boolean> {
        val api: Api = api ?: return RawResult.Failure(RawStatus.SYMBOL_MISSING)
        return backendIsPortal(api)
    }

    /**
     * Asks libsecret which backend it selected: `secret_backend_get` on a private thread-default main context, iterated here until
     * the callback fired (the shape of libsecret's own `_sync` functions). Answers `false` when libsecret has no backend API (< 0.20).
     */
    private fun backendIsPortal(api: Api): RawResult<Boolean> {
        val backendApi: BackendApi = api.backend ?: return RawResult.Success(false)
        return ledger.scope().use { scope: NativeScope ->
            val request = BackendWait(backendApi.getFinish, scope.arena.allocate(ValueLayout.ADDRESS))
            val context: MemorySegment = api.contextNew.pointer()
            if (context.address() == 0L) throw OutOfMemoryError("g_main_context_new returned NULL")
            // Released last of the context's two registrations: after the pop. A callback that already fired is the only one a drain can
            // meet (libsecret's own cleanup does the same); an unfired one never calls back once nobody iterates the context.
            scope.own(context) { held: MemorySegment ->
                if (request.fired) while (api.contextIteration.int(held, 0) != 0) {
                    // Dispatches what is still queued on the context, as libsecret's own cleanup does.
                }
                api.contextUnref.invoke(held)
            }
            api.contextPush.invoke(context)
            scope.own(context) { held: MemorySegment -> api.contextPop.invoke(held) }
            // The stub lives in the call's arena, which closes after every release above: the callback cannot outlive it.
            val callback: MemorySegment = foreign.upcallStub(
                MethodHandles.lookup()
                    .findVirtual(
                        BackendWait::class.java, "onReady",
                        MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
                    ).bindTo(request),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                scope.arena,
            )
            backendApi.get.invoke(0, MemorySegment.NULL, callback, MemorySegment.NULL)
            // The task may complete inside the call above, so test before the first (blocking) iteration.
            while (!request.fired) api.contextIteration.int(context, 1)
            request.failure?.let { failure: Throwable -> throw failure }
            if (request.error.get(ValueLayout.ADDRESS, 0L).address() != 0L) return@use RawResult.Failure(api.takeError(scope, request.error))
            // Borrowed, not owned: see the class KDoc.
            val backend: MemorySegment = request.backend
            if (backend.address() == 0L) return@use RawResult.Failure(RawStatus(G_IO_ERROR_FAILED, G_IO_ERROR_DOMAIN))
            val name: MemorySegment = api.typeNameFromInstance.pointer(backend)
            val typeName: String = if (name.address() == 0L) "" else name.reinterpret(MAX_TYPE_NAME).getString(0L)
            // libsecret's file backend is the one it selects for the Flatpak Secret portal (and for Snap or SECRET_BACKEND=file).
            RawResult.Success(typeName == FILE_BACKEND_TYPE)
        }
    }

    /**
     * The state of one `secret_backend_get` request, written by the callback that libsecret invokes on the iterating thread. The
     * callback finishes the request itself (the `GAsyncResult` is valid only during the callback) and must never throw: an exception
     * escaping an upcall ends the JVM, so any failure is recorded and rethrown by the caller outside the upcall.
     */
    private class BackendWait(private val finish: Downcall, val error: MemorySegment) {
        @Volatile var fired: Boolean = false
        var backend: MemorySegment = MemorySegment.NULL
        var failure: Throwable? = null

        @Suppress("unused", "UNUSED_PARAMETER")
        fun onReady(source: MemorySegment, result: MemorySegment, userData: MemorySegment) {
            if (fired) return
            try {
                backend = finish.pointer(result, error)
            } catch (thrown: Throwable) {
                failure = thrown
            } finally {
                fired = true
            }
        }
    }

    /** A `SecretSchema` (592 bytes, 64-bit) naming the string attributes in [attributes], owned by [scope]'s arena. */
    private fun schema(scope: NativeScope, attributes: Map<String, String>): MemorySegment {
        require(attributes.size < MAX_SCHEMA_ATTRIBUTES) { "A libsecret schema holds at most 32 attributes" }
        val schema: MemorySegment = scope.arena.allocate(SCHEMA_SIZE)
        schema.set(ValueLayout.ADDRESS, 0L, cString(scope, SCHEMA_NAME))
        // flags at 8 stay SECRET_SCHEMA_NONE; attributes[i] = { name, type } at 16 + 16 * i; the NULL-name entry after them ends the list.
        attributes.keys.sorted().forEachIndexed { index: Int, name: String ->
            val entry: Long = SCHEMA_ATTRIBUTES_OFFSET + SCHEMA_ATTRIBUTE_SIZE * index
            schema.set(ValueLayout.ADDRESS, entry, cString(scope, name))
            schema.set(ValueLayout.JAVA_INT, entry + SCHEMA_ATTRIBUTE_TYPE_OFFSET, SECRET_SCHEMA_ATTRIBUTE_STRING)
        }
        return schema
    }

    /** A string-to-string `GHashTable` of [attributes]; the table is unreferenced before the strings it points at are freed. */
    private fun table(scope: NativeScope, api: Api, attributes: Map<String, String>): MemorySegment {
        val table: MemorySegment = api.hashTableNew.pointer(api.strHash, api.strEqual)
        if (table.address() == 0L) throw OutOfMemoryError("g_hash_table_new returned NULL")
        scope.own(table) { held: MemorySegment -> api.hashTableUnref.invoke(held) }
        for ((name: String, value: String) in attributes) {
            api.hashTableInsert.int(table, cString(scope, name), cString(scope, value))
        }
        return table
    }

    private fun cString(scope: NativeScope, text: String): MemorySegment = scope.arena.allocateFrom(text)

    /** `secret_backend_get` and `secret_backend_get_finish`, exported by libsecret 0.20.1 and newer. */
    private class BackendApi(val get: Downcall, val getFinish: Downcall)

    /** Everything libsecret, gio and glib that the binding calls, resolved once. */
    private class Api(
        val searchv: Downcall,
        val retrieve: Downcall,
        val valueGet: Downcall,
        val valueUnref: Downcall,
        val store: Downcall,
        val clear: Downcall,
        val collectionForAlias: Downcall,
        val collectionGetLocked: Downcall,
        val backend: BackendApi?,
        val contextNew: Downcall,
        val contextPush: Downcall,
        val contextPop: Downcall,
        val contextUnref: Downcall,
        val contextIteration: Downcall,
        val typeNameFromInstance: Downcall,
        val itemGetType: Downcall,
        val itemGetLocked: Downcall,
        val checkInstanceIsA: Downcall,
        val objectUnref: Downcall,
        val listFree: Downcall,
        val hashTableNew: Downcall,
        val hashTableInsert: Downcall,
        val hashTableUnref: Downcall,
        val strHash: MemorySegment,
        val strEqual: MemorySegment,
        val errorFree: Downcall,
        val free: Downcall,
        val quarkToString: Downcall,
        val remoteError: Downcall,
        val encodeError: Downcall,
    ) {
        /** Unreferences every object of [head] and frees the list cells. */
        fun freeList(head: MemorySegment) {
            var node: MemorySegment = head
            while (node.address() != 0L) {
                val cell: MemorySegment = node.reinterpret(GLIST_SIZE)
                val data: MemorySegment = cell.get(ValueLayout.ADDRESS, 0L)
                if (data.address() != 0L) objectUnref.invoke(data)
                node = cell.get(ValueLayout.ADDRESS, GLIST_NEXT_OFFSET)
            }
            listFree.invoke(head)
        }

        /** Whether [retrievable] is a `SecretItem` that is locked. */
        fun isLockedItem(retrievable: MemorySegment): Boolean {
            val itemType: Long = itemGetType.long()
            if (checkInstanceIsA.int(retrievable, itemType) == 0) return false
            return itemGetLocked.int(retrievable) != 0
        }

        /**
         * Reads the `GError` behind [errorSlot] into a [RawStatus] (domain name, code, D-Bus error name when there is one) and
         * frees it. The name strings are owned by [scope] and freed with it.
         */
        fun takeError(scope: NativeScope, errorSlot: MemorySegment): RawStatus {
            val error: MemorySegment = errorSlot.get(ValueLayout.ADDRESS, 0L)
            scope.own(error) { held: MemorySegment -> errorFree.invoke(held) }
            val fields: MemorySegment = error.reinterpret(GERROR_SIZE)
            val domainQuark: Int = fields.get(ValueLayout.JAVA_INT, 0L)
            val code: Int = fields.get(ValueLayout.JAVA_INT, GERROR_CODE_OFFSET)
            val domainName: MemorySegment = quarkToString.pointer(domainQuark)
            val domain: String? = if (domainName.address() == 0L) null else domainName.reinterpret(MAX_TYPE_NAME).getString(0L)
            var name: String? = ownedString(scope, remoteError.pointer(error))
            if (name == null && (domain == DBUS_ERROR_DOMAIN || domain == SECRET_ERROR_DOMAIN)) {
                name = ownedString(scope, encodeError.pointer(error))
            }
            // Never report a failure as code 0 with no domain: that is the success value.
            return RawStatus(if (code == 0 && domain == null && name == null) G_IO_ERROR_FAILED else code, domain, name)
        }

        private fun ownedString(scope: NativeScope, pointer: MemorySegment): String? {
            if (pointer.address() == 0L) return null
            scope.own(pointer) { held: MemorySegment -> free.invoke(held) }
            return pointer.reinterpret(MAX_TYPE_NAME).getString(0L)
        }

        companion object {
            private val ADDR = ValueLayout.ADDRESS
            private val INT = ValueLayout.JAVA_INT
            private val LONG = ValueLayout.JAVA_LONG

            /** Resolves everything; `null` when libsecret cannot be opened or any symbol is missing (never an exception for those). */
            fun load(foreign: Foreign): Api? {
                val secret: SymbolLookup = foreign.tryOpen(LIBSECRET) ?: return null
                // libsecret links glib, gobject and gio, so its handle finds their symbols too; the sonames are a fallback.
                var all: SymbolLookup = secret
                for (library: String in listOf(LIBGOBJECT, LIBGLIB, LIBGIO)) {
                    val extra: SymbolLookup = foreign.tryOpen(library) ?: continue
                    all = all.or(extra)
                }
                // The symbol that decides "libsecret is too old" (plan 3.5): resolved explicitly, before anything else.
                if (foreign.find(all, "secret_password_search_sync") == null) return null
                fun fn(name: String, descriptor: FunctionDescriptor): Downcall? =
                    foreign.find(all, name)?.let { address: MemorySegment -> foreign.downcall(name, address, descriptor) }
                // Resolved as a pair: one without the other is as good as none.
                val backendGet: Downcall? = fn("secret_backend_get", FunctionDescriptor.ofVoid(INT, ADDR, ADDR, ADDR))
                val backendFinish: Downcall? = fn("secret_backend_get_finish", FunctionDescriptor.of(ADDR, ADDR, ADDR))
                val backend: BackendApi? = if (backendGet != null && backendFinish != null) BackendApi(backendGet, backendFinish) else null
                val strHash: MemorySegment = foreign.find(all, "g_str_hash") ?: return null
                val strEqual: MemorySegment = foreign.find(all, "g_str_equal") ?: return null
                return Api(
                    searchv = fn("secret_password_searchv_sync", FunctionDescriptor.of(ADDR, ADDR, ADDR, INT, ADDR, ADDR)) ?: return null,
                    retrieve = fn("secret_retrievable_retrieve_secret_sync", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR)) ?: return null,
                    valueGet = fn("secret_value_get", FunctionDescriptor.of(ADDR, ADDR, ADDR)) ?: return null,
                    valueUnref = fn("secret_value_unref", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    store = fn("secret_password_storev_sync", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR, ADDR)) ?: return null,
                    clear = fn("secret_password_clearv_sync", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR, ADDR)) ?: return null,
                    collectionForAlias = fn("secret_collection_for_alias_sync", FunctionDescriptor.of(ADDR, ADDR, ADDR, INT, ADDR, ADDR)) ?: return null,
                    collectionGetLocked = fn("secret_collection_get_locked", FunctionDescriptor.of(INT, ADDR)) ?: return null,
                    // Optional pair (libsecret 0.20.1 and newer): absent means no portal backend, not a broken library.
                    backend = backend,
                    contextNew = fn("g_main_context_new", FunctionDescriptor.of(ADDR)) ?: return null,
                    contextPush = fn("g_main_context_push_thread_default", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    contextPop = fn("g_main_context_pop_thread_default", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    contextUnref = fn("g_main_context_unref", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    contextIteration = fn("g_main_context_iteration", FunctionDescriptor.of(INT, ADDR, INT)) ?: return null,
                    typeNameFromInstance = fn("g_type_name_from_instance", FunctionDescriptor.of(ADDR, ADDR)) ?: return null,
                    itemGetType = fn("secret_item_get_type", FunctionDescriptor.of(LONG)) ?: return null,
                    itemGetLocked = fn("secret_item_get_locked", FunctionDescriptor.of(INT, ADDR)) ?: return null,
                    checkInstanceIsA = fn("g_type_check_instance_is_a", FunctionDescriptor.of(INT, ADDR, LONG)) ?: return null,
                    objectUnref = fn("g_object_unref", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    listFree = fn("g_list_free", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    hashTableNew = fn("g_hash_table_new", FunctionDescriptor.of(ADDR, ADDR, ADDR)) ?: return null,
                    hashTableInsert = fn("g_hash_table_insert", FunctionDescriptor.of(INT, ADDR, ADDR, ADDR)) ?: return null,
                    hashTableUnref = fn("g_hash_table_unref", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    strHash = strHash,
                    strEqual = strEqual,
                    errorFree = fn("g_error_free", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    free = fn("g_free", FunctionDescriptor.ofVoid(ADDR)) ?: return null,
                    quarkToString = fn("g_quark_to_string", FunctionDescriptor.of(ADDR, INT)) ?: return null,
                    remoteError = fn("g_dbus_error_get_remote_error", FunctionDescriptor.of(ADDR, ADDR)) ?: return null,
                    encodeError = fn("g_dbus_error_encode_gerror", FunctionDescriptor.of(ADDR, ADDR)) ?: return null,
                )
            }
        }
    }

    internal companion object {
        const val LIBSECRET: String = "libsecret-1.so.0"
        const val LIBGOBJECT: String = "libgobject-2.0.so.0"
        const val LIBGLIB: String = "libglib-2.0.so.0"
        const val LIBGIO: String = "libgio-2.0.so.0"

        /** The libsecret schema of every data-key item (plan 4.7); the attributes `service` and `directory` come from the caller. */
        const val SCHEMA_NAME: String = "io.github.jamal_wia.kmptoolkit.storage.Key"
        const val COLLECTION_DEFAULT: String = "default"
        const val FILE_BACKEND_TYPE: String = "SecretFileBackend"
        const val DBUS_ERROR_DOMAIN: String = "g-dbus-error-quark"
        const val SECRET_ERROR_DOMAIN: String = "secret-error"
        const val G_IO_ERROR_DOMAIN: String = "g-io-error-quark"

        /** `G_IO_ERROR_FAILED`. */
        const val G_IO_ERROR_FAILED: Int = 0

        const val SECRET_SEARCH_ALL: Int = 1 shl 1
        const val SECRET_SEARCH_UNLOCK: Int = 1 shl 2
        const val SECRET_SCHEMA_ATTRIBUTE_STRING: Int = 0

        // sizeof(SecretSchema) on 64-bit: name 8, flags 4 + 4 padding, 32 attributes of 16, reserved 4 + 4 padding, 7 pointers of 8.
        private const val SCHEMA_SIZE: Long = 592L
        private const val SCHEMA_ATTRIBUTES_OFFSET: Long = 16L
        private const val SCHEMA_ATTRIBUTE_SIZE: Long = 16L
        private const val SCHEMA_ATTRIBUTE_TYPE_OFFSET: Long = 8L

        // The last of the 32 slots must stay the NULL terminator.
        private const val MAX_SCHEMA_ATTRIBUTES: Int = 32
        private const val GLIST_SIZE: Long = 24L
        private const val GLIST_NEXT_OFFSET: Long = 8L
        private const val GERROR_SIZE: Long = 16L
        private const val GERROR_CODE_OFFSET: Long = 4L
        private const val MAX_TYPE_NAME: Long = 4096L
        private const val MAX_SECRET_BYTES: Long = 1L shl 20
    }
}
