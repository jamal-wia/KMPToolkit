package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sto-ffm (STO-7): the Linux FFM binding must load against the libraries that really exist.
 *
 * Contract (plan 3.5, 4.7): libsecret 0.19 or newer is supported; only a libsecret without `secret_password_search_sync`
 * (older than 0.19) answers `SYMBOL_MISSING`. So a binding over the exact export table of a real libsecret must NOT answer
 * `SYMBOL_MISSING`, and a raw call must reach libsecret.
 *
 * The export tables are the symbol list Debian ships for `libsecret-1.so.0` (package 0.21.8.2-2, file
 * `debian/libsecret-1-0.symbols`): every `secret_*` symbol with the libsecret version that first exported it. A table "as of
 * version V" exports the symbols whose version is at most V. glib, gobject and gio are modelled as exporting every `g_*` name
 * (libsecret links them; their exact tables are not this test's subject). The functions the `clear` call runs are upcall stubs
 * that count; every other exported symbol is a dummy address that is bound but never called.
 *
 * Mutation by reading: binding any symbol that libsecret does not export (for example `secret_backend_get_sync`, which does not
 * exist: libsecret has only the asynchronous `secret_backend_get` / `secret_backend_get_finish`, since 0.20) makes `Api.load`
 * answer `null`, and every call answers `SYMBOL_MISSING` on every real Linux: these tests go red.
 */
class FfmSecretServiceBreakerR1Test {

    private val arena: Arena = Arena.ofShared()
    private val attributes: Map<String, String> = mapOf("service" to "s.kmptoolkit.securestorage", "directory" to "0".repeat(32))

    @AfterTest
    fun tearDown() {
        arena.close()
    }

    /** Counting stand-ins for the four functions a `clear` runs. */
    private inner class FakeGlib {
        val tablesLive: MutableMap<Long, Int> = ConcurrentHashMap()
        val clearCalls: AtomicInteger = AtomicInteger()
        val inserts: AtomicInteger = AtomicInteger()
        val stubs: MutableMap<String, MemorySegment> = ConcurrentHashMap()

        init {
            val a = ValueLayout.ADDRESS
            val i = ValueLayout.JAVA_INT
            val segment = MemorySegment::class.java
            stub("g_hash_table_new", "hashTableNew", FunctionDescriptor.of(a, a, a), MethodType.methodType(segment, segment, segment))
            stub("g_hash_table_insert", "hashTableInsert", FunctionDescriptor.of(i, a, a, a), MethodType.methodType(Int::class.java, segment, segment, segment))
            stub("g_hash_table_unref", "hashTableUnref", FunctionDescriptor.ofVoid(a), MethodType.methodType(Void.TYPE, segment))
            stub(
                "secret_password_clearv_sync", "clearvSync", FunctionDescriptor.of(i, a, a, a, a),
                MethodType.methodType(Int::class.java, segment, segment, segment, segment),
            )
        }

        private fun stub(symbol: String, method: String, descriptor: FunctionDescriptor, type: MethodType) {
            val handle = MethodHandles.lookup().findVirtual(FakeGlib::class.java, method, type).bindTo(this)
            stubs[symbol] = Linker.nativeLinker().upcallStub(handle, descriptor, arena)
        }

        @Suppress("unused") fun hashTableNew(hash: MemorySegment, equal: MemorySegment): MemorySegment {
            val table: MemorySegment = arena.allocate(16)
            tablesLive[table.address()] = 1
            return table
        }

        @Suppress("unused") fun hashTableInsert(table: MemorySegment, key: MemorySegment, value: MemorySegment): Int {
            inserts.incrementAndGet()
            return 1
        }

        @Suppress("unused") fun hashTableUnref(table: MemorySegment) {
            tablesLive.remove(table.address())
        }

        @Suppress("unused") fun clearvSync(schema: MemorySegment, table: MemorySegment, cancellable: MemorySegment, error: MemorySegment): Int {
            clearCalls.incrementAndGet()
            return 1
        }
    }

    /** A libsecret (plus glib) whose exports are the real table as of [version]; records every name the binding asked for. */
    private class RealExports(private val version: String, private val fake: Map<String, MemorySegment>) : SymbolLookup {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        val refused: MutableList<String> = CopyOnWriteArrayList()

        override fun find(name: String): Optional<MemorySegment> {
            asked.add(name)
            val exported: Boolean = name.startsWith("g_") || LIBSECRET_EXPORTS[name]?.let { since: String -> compareVersions(since, version) <= 0 } == true
            if (!exported) {
                refused.add(name)
                return Optional.empty()
            }
            return Optional.of(fake[name] ?: MemorySegment.ofAddress(DUMMY_ADDRESS))
        }
    }

    private fun clearAgainstLibsecret(version: String) {
        val glib = FakeGlib()
        val exports = RealExports(version, glib.stubs)
        val ledger = NativeLedger()
        val service = FfmSecretService(Foreign({ exports }, Linker.nativeLinker()), ledger)

        val status: RawStatus = service.clear(attributes)

        val context = "libsecret $version; symbols the binding asked for and the library does not export: ${exports.refused}"
        assertTrue(!status.isSymbolMissing, "a libsecret $version must not answer SYMBOL_MISSING ($context)")
        assertEquals(RawStatus.SUCCESS, status, context)
        assertEquals(1, glib.clearCalls.get(), "control: the clear reached libsecret exactly once ($context)")
        assertEquals(attributes.size, glib.inserts.get(), "control: both attributes went into the table ($context)")
        assertEquals(emptyMap(), glib.tablesLive, "the attribute table must be unreferenced ($context)")
        assertEquals(0, ledger.outstanding, "every glib object must be released ($context)")
    }

    @Test
    fun `the binding loads against the export table of libsecret 0_21 and a clear reaches libsecret`() {
        clearAgainstLibsecret("0.21.8.2")
    }

    @Test
    fun `the binding loads against the export table of libsecret 0_20_4, the version of Ubuntu 22_04 and RHEL 9`() {
        clearAgainstLibsecret("0.20.4")
    }

    @Test
    fun `libsecret 0_19_1 has secret_password_search_sync, so it is supported and is not SYMBOL_MISSING`() {
        clearAgainstLibsecret("0.19.1")
    }

    @Test
    fun `control - the export table of libsecret 0_18_6 lacks the search symbol and is SYMBOL_MISSING`() {
        val exports = RealExports("0.18.6", emptyMap())
        val service = FfmSecretService(Foreign({ exports }, Linker.nativeLinker()), NativeLedger())

        assertTrue(service.clear(attributes).isSymbolMissing, "0.18.6 must be SYMBOL_MISSING: ${exports.refused}")
        assertTrue("secret_password_search_sync" in exports.refused, "control: the table model refuses the 0.19 symbol on 0.18.6")
    }

    private companion object {
        const val DUMMY_ADDRESS: Long = 0x1000L

        /** -1, 0 or 1 for dotted numeric versions ("0.20.1" < "0.21.8.2"). */
        fun compareVersions(left: String, right: String): Int {
            val a: List<Int> = left.split('.').map(String::toInt)
            val b: List<Int> = right.split('.').map(String::toInt)
            for (index: Int in 0 until maxOf(a.size, b.size)) {
                val difference: Int = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (difference != 0) return difference
            }
            return 0
        }

        /** `debian/libsecret-1-0.symbols` of libsecret 0.21.8.2-2: symbol and the version that first exported it. */
        val LIBSECRET_EXPORTS: Map<String, String> = """
            secret_attributes_build 0.7
            secret_attributes_buildv 0.7
            secret_attributes_validate 0.21.2
            secret_backend_flags_get_type 0.20.1
            secret_backend_get 0.20.1
            secret_backend_get_finish 0.20.1
            secret_backend_get_type 0.20.1
            secret_collection_create 0.7
            secret_collection_create_finish 0.7
            secret_collection_create_flags_get_type 0.7
            secret_collection_create_sync 0.7
            secret_collection_delete 0.7
            secret_collection_delete_finish 0.7
            secret_collection_delete_sync 0.7
            secret_collection_flags_get_type 0.7
            secret_collection_for_alias 0.7
            secret_collection_for_alias_finish 0.7
            secret_collection_for_alias_sync 0.7
            secret_collection_get_created 0.7
            secret_collection_get_flags 0.7
            secret_collection_get_items 0.7
            secret_collection_get_label 0.7
            secret_collection_get_locked 0.7
            secret_collection_get_modified 0.7
            secret_collection_get_service 0.7
            secret_collection_get_type 0.7
            secret_collection_load_items 0.7
            secret_collection_load_items_finish 0.7
            secret_collection_load_items_sync 0.7
            secret_collection_new_for_dbus_path 0.7
            secret_collection_new_for_dbus_path_finish 0.7
            secret_collection_new_for_dbus_path_sync 0.7
            secret_collection_refresh 0.7
            secret_collection_search 0.7
            secret_collection_search_finish 0.7
            secret_collection_search_for_dbus_paths 0.7
            secret_collection_search_for_dbus_paths_finish 0.7
            secret_collection_search_for_dbus_paths_sync 0.7
            secret_collection_search_sync 0.7
            secret_collection_set_label 0.7
            secret_collection_set_label_finish 0.7
            secret_collection_set_label_sync 0.7
            secret_error_get_quark 0.7
            secret_error_get_type 0.7
            secret_file_backend_get_type 0.20.1
            secret_file_collection_clear 0.20.1
            secret_file_collection_get_type 0.20.1
            secret_file_collection_lock 0.21.8.2
            secret_file_collection_replace 0.20.1
            secret_file_collection_search 0.20.1
            secret_file_collection_unlock 0.21.8.2
            secret_file_collection_write 0.20.1
            secret_file_collection_write_finish 0.20.1
            secret_file_item_deserialize 0.20.1
            secret_file_item_get_type 0.20.1
            secret_file_item_serialize 0.20.1
            secret_get_schema 0.18.6
            secret_item_create 0.7
            secret_item_create_finish 0.7
            secret_item_create_flags_get_type 0.7
            secret_item_create_sync 0.7
            secret_item_delete 0.7
            secret_item_delete_finish 0.7
            secret_item_delete_sync 0.7
            secret_item_flags_get_type 0.7
            secret_item_get_attributes 0.7
            secret_item_get_created 0.7
            secret_item_get_flags 0.7
            secret_item_get_label 0.7
            secret_item_get_locked 0.7
            secret_item_get_modified 0.7
            secret_item_get_schema_name 0.7
            secret_item_get_secret 0.7
            secret_item_get_service 0.7
            secret_item_get_type 0.7
            secret_item_load_secret 0.7
            secret_item_load_secret_finish 0.7
            secret_item_load_secret_sync 0.7
            secret_item_load_secrets 0.7
            secret_item_load_secrets_finish 0.7
            secret_item_load_secrets_sync 0.7
            secret_item_new_for_dbus_path 0.7
            secret_item_new_for_dbus_path_finish 0.7
            secret_item_new_for_dbus_path_sync 0.7
            secret_item_refresh 0.7
            secret_item_set_attributes 0.7
            secret_item_set_attributes_finish 0.7
            secret_item_set_attributes_sync 0.7
            secret_item_set_label 0.7
            secret_item_set_label_finish 0.7
            secret_item_set_label_sync 0.7
            secret_item_set_secret 0.7
            secret_item_set_secret_finish 0.7
            secret_item_set_secret_sync 0.7
            secret_password_clear 0.7
            secret_password_clear_finish 0.7
            secret_password_clear_sync 0.7
            secret_password_clearv 0.7
            secret_password_clearv_sync 0.7
            secret_password_free 0.7
            secret_password_lookup 0.7
            secret_password_lookup_binary_finish 0.19.1
            secret_password_lookup_binary_sync 0.19.1
            secret_password_lookup_finish 0.7
            secret_password_lookup_nonpageable_finish 0.7
            secret_password_lookup_nonpageable_sync 0.7
            secret_password_lookup_sync 0.7
            secret_password_lookupv 0.7
            secret_password_lookupv_binary_sync 0.19.1
            secret_password_lookupv_nonpageable_sync 0.7
            secret_password_lookupv_sync 0.7
            secret_password_search 0.19.1
            secret_password_search_finish 0.19.1
            secret_password_search_sync 0.19.1
            secret_password_searchv 0.19.1
            secret_password_searchv_sync 0.19.1
            secret_password_store 0.7
            secret_password_store_binary 0.19.1
            secret_password_store_binary_sync 0.19.1
            secret_password_store_finish 0.7
            secret_password_store_sync 0.7
            secret_password_storev 0.7
            secret_password_storev_binary 0.19.1
            secret_password_storev_binary_sync 0.19.1
            secret_password_storev_sync 0.7
            secret_password_wipe 0.7
            secret_prompt_get_type 0.7
            secret_prompt_perform 0.7
            secret_prompt_perform_finish 0.7
            secret_prompt_perform_sync 0.7
            secret_prompt_run 0.7
            secret_retrievable_get_attributes 0.19.1
            secret_retrievable_get_created 0.19.1
            secret_retrievable_get_label 0.19.1
            secret_retrievable_get_modified 0.19.1
            secret_retrievable_get_type 0.19.1
            secret_retrievable_retrieve_secret 0.19.1
            secret_retrievable_retrieve_secret_finish 0.19.1
            secret_retrievable_retrieve_secret_sync 0.19.1
            secret_schema_attribute_get_type 0.7
            secret_schema_attribute_type_get_type 0.7
            secret_schema_flags_get_type 0.7
            secret_schema_get_type 0.7
            secret_schema_new 0.7
            secret_schema_newv 0.7
            secret_schema_ref 0.7
            secret_schema_type_get_type 0.18.6
            secret_schema_unref 0.7
            secret_search_flags_get_type 0.7
            secret_service_clear 0.7
            secret_service_clear_finish 0.7
            secret_service_clear_sync 0.7
            secret_service_create_collection_dbus_path 0.7
            secret_service_create_collection_dbus_path_finish 0.7
            secret_service_create_collection_dbus_path_sync 0.7
            secret_service_create_item_dbus_path 0.7
            secret_service_create_item_dbus_path_finish 0.7
            secret_service_create_item_dbus_path_sync 0.7
            secret_service_decode_dbus_secret 0.18
            secret_service_delete_item_dbus_path 0.7
            secret_service_delete_item_dbus_path_finish 0.7
            secret_service_delete_item_dbus_path_sync 0.7
            secret_service_disconnect 0.7
            secret_service_encode_dbus_secret 0.18
            secret_service_ensure_session 0.7
            secret_service_ensure_session_finish 0.7
            secret_service_ensure_session_sync 0.7
            secret_service_flags_get_type 0.7
            secret_service_get 0.7
            secret_service_get_collection_gtype 0.18
            secret_service_get_collections 0.7
            secret_service_get_finish 0.7
            secret_service_get_flags 0.7
            secret_service_get_item_gtype 0.18
            secret_service_get_secret_for_dbus_path 0.7
            secret_service_get_secret_for_dbus_path_finish 0.7
            secret_service_get_secret_for_dbus_path_sync 0.7
            secret_service_get_secrets_for_dbus_paths 0.7
            secret_service_get_secrets_for_dbus_paths_finish 0.7
            secret_service_get_secrets_for_dbus_paths_sync 0.7
            secret_service_get_session_algorithms 0.7
            secret_service_get_session_dbus_path 0.7
            secret_service_get_sync 0.7
            secret_service_get_type 0.7
            secret_service_load_collections 0.7
            secret_service_load_collections_finish 0.7
            secret_service_load_collections_sync 0.7
            secret_service_lock 0.7
            secret_service_lock_dbus_paths 0.7
            secret_service_lock_dbus_paths_finish 0.7
            secret_service_lock_dbus_paths_sync 0.7
            secret_service_lock_finish 0.7
            secret_service_lock_sync 0.7
            secret_service_lookup 0.7
            secret_service_lookup_finish 0.7
            secret_service_lookup_sync 0.7
            secret_service_open 0.18
            secret_service_open_finish 0.18
            secret_service_open_sync 0.18
            secret_service_prompt 0.7
            secret_service_prompt_at_dbus_path 0.7
            secret_service_prompt_at_dbus_path_finish 0.7
            secret_service_prompt_at_dbus_path_sync 0.7
            secret_service_prompt_finish 0.7
            secret_service_prompt_sync 0.7
            secret_service_read_alias_dbus_path 0.7
            secret_service_read_alias_dbus_path_finish 0.7
            secret_service_read_alias_dbus_path_sync 0.7
            secret_service_search 0.7
            secret_service_search_finish 0.7
            secret_service_search_for_dbus_paths 0.7
            secret_service_search_for_dbus_paths_finish 0.7
            secret_service_search_for_dbus_paths_sync 0.7
            secret_service_search_sync 0.7
            secret_service_set_alias 0.7
            secret_service_set_alias_finish 0.7
            secret_service_set_alias_sync 0.7
            secret_service_set_alias_to_dbus_path 0.7
            secret_service_set_alias_to_dbus_path_finish 0.7
            secret_service_set_alias_to_dbus_path_sync 0.7
            secret_service_store 0.7
            secret_service_store_finish 0.7
            secret_service_store_sync 0.7
            secret_service_unlock 0.7
            secret_service_unlock_dbus_paths 0.7
            secret_service_unlock_dbus_paths_finish 0.7
            secret_service_unlock_dbus_paths_sync 0.7
            secret_service_unlock_finish 0.7
            secret_service_unlock_sync 0.7
            secret_value_get 0.7
            secret_value_get_content_type 0.7
            secret_value_get_text 0.18
            secret_value_get_type 0.7
            secret_value_new 0.7
            secret_value_new_full 0.7
            secret_value_ref 0.7
            secret_value_unref 0.7
            secret_value_unref_to_password 0.19.1
        """.trimIndent().lines().filter(String::isNotBlank).associate { line: String ->
            val (name: String, since: String) = line.trim().split(' ')
            name to since
        }
    }
}
