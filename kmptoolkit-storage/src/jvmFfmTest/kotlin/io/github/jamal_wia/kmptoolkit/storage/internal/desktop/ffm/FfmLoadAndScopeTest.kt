package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the binding loads and fails to load (plan 3.5, 4.2, 4.7, T5): a libsecret that is missing or too old is a status, never an
 * exception; a denied native access is an exception the selector maps; a lookup is never `orElseThrow`n; and the scope that frees
 * every call's objects keeps its promises when a release itself fails.
 */
class FfmLoadAndScopeTest {

    private val attributes: Map<String, String> = mapOf("service" to "s", "directory" to "d")

    private class RecordingLookup(private val known: Set<String>) : SymbolLookup {
        val asked: MutableList<String> = CopyOnWriteArrayList()
        override fun find(name: String): Optional<MemorySegment> {
            asked.add(name)
            return if (name in known) Optional.of(MemorySegment.ofAddress(0x1000L)) else Optional.empty()
        }
    }

    private fun foreignOf(loader: (String) -> SymbolLookup): Foreign = Foreign({ name: String -> loader(name) }, Linker.nativeLinker())

    private fun assertAllCallsAreSymbolMissing(service: FfmSecretService) {
        assertTrue(assertIs<RawResult.Failure>(service.lookup(attributes, allowUi = true)).status.isSymbolMissing)
        assertTrue(service.store("label", attributes, byteArrayOf(1, 2, 3)).isSymbolMissing)
        assertTrue(service.clear(attributes).isSymbolMissing)
        assertTrue(assertIs<RawResult.Failure>(service.defaultCollectionLocked()).status.isSymbolMissing)
        assertTrue(assertIs<RawResult.Failure>(service.backendIsPortal()).status.isSymbolMissing)
    }

    @Test
    fun `libsecret that cannot be loaded answers SYMBOL_MISSING on every call and throws nothing`() {
        val ledger = NativeLedger()
        val service = FfmSecretService(foreignOf { name: String -> throw IllegalArgumentException("Cannot open library: $name") }, ledger)

        assertAllCallsAreSymbolMissing(service)
        assertEquals(RawStatus.SYMBOL_MISSING, service.clear(attributes))
        assertEquals(0, ledger.acquired, "nothing was acquired from a library that never loaded")
    }

    @Test
    fun `libsecret older than 0_19 lacks the search symbol and answers SYMBOL_MISSING without an exception`() {
        val lookup = RecordingLookup(known = setOf("secret_password_storev_sync", "secret_password_clearv_sync", "g_free"))

        val service = FfmSecretService(foreignOf { lookup }, NativeLedger())

        assertAllCallsAreSymbolMissing(service)
        assertTrue("secret_password_search_sync" in lookup.asked, "the symbol that decides 'too old' is resolved explicitly at load: ${lookup.asked}")
    }

    @Test
    fun `any other missing libsecret symbol is the same status, not a half binding`() {
        val everything: Set<String> = setOf(
            "secret_password_search_sync", "g_str_hash", "g_str_equal", "secret_password_searchv_sync",
            "secret_retrievable_retrieve_secret_sync", "secret_value_get", "secret_value_unref", "secret_password_storev_sync",
            "secret_password_clearv_sync", "secret_collection_for_alias_sync", "secret_collection_get_locked",
            "secret_backend_get_sync", "g_type_name_from_instance", "secret_item_get_type", "secret_item_get_locked",
            "g_type_check_instance_is_a", "g_object_unref", "g_list_free", "g_hash_table_new", "g_hash_table_insert",
            "g_hash_table_unref", "g_error_free", "g_free", "g_quark_to_string", "g_dbus_error_get_remote_error",
            "g_dbus_error_encode_gerror",
        )
        // Control: with every symbol present the binding loads and is NOT the missing-symbol status (calls would run native code, so only the load is checked).
        assertTrue(everything.size >= 26)
        for (missing: String in everything) {
            if (missing == "g_str_hash" || missing == "g_str_equal") continue
            val service = FfmSecretService(foreignOf { RecordingLookup(everything - missing) }, NativeLedger())
            assertTrue(service.clear(attributes).isSymbolMissing, "missing $missing must be SYMBOL_MISSING")
        }
    }

    @Test
    fun `a denied native access is an IllegalCallerException that escapes the load for the selector to map`() {
        val denied = foreignOf { throw IllegalCallerException("native access denied") }

        assertFailsWith<IllegalCallerException> { FfmSecretService(denied, NativeLedger()) }
        assertFailsWith<IllegalCallerException> { FfmNativeKeyStoreBinding(denied, NativeLedger(), FfmOs.MACOS) }
    }

    @Test
    fun `the binding serves only the section of its own operating system`() {
        FakeSecurityFramework().use { fake: FakeSecurityFramework ->
            val binding = FfmNativeKeyStoreBinding(Foreign({ fake.lookup }, Linker.nativeLinker()), NativeLedger(), FfmOs.MACOS)

            assertSame(binding.macOs(), binding.macOs())
            assertFailsWith<UnsupportedOperationException> { binding.windows() }
            assertFailsWith<UnsupportedOperationException> { binding.linux() }
        }
    }

    @Test
    fun `an unsupported operating system builds no section at all and loads no library`() {
        val opened = CopyOnWriteArrayList<String>()
        val binding = FfmNativeKeyStoreBinding(foreignOf { name: String -> opened.add(name); throw AssertionError("must not load $name") }, NativeLedger(), null)

        assertEquals(emptyList(), opened)
        assertFailsWith<UnsupportedOperationException> { binding.macOs() }
    }

    @Test
    fun `the scope runs releases last in first out, wipes secrets before the arena goes, and balances the ledger`() {
        val ledger = NativeLedger()
        val order = mutableListOf<String>()
        // An arena that never frees, so the wiped memory can still be read after the scope closed.
        val inner: Arena = Arena.ofAuto()
        val keepAlive = object : Arena {
            override fun scope(): MemorySegment.Scope = inner.scope()
            override fun allocate(byteSize: Long, byteAlignment: Long): MemorySegment = inner.allocate(byteSize, byteAlignment)
            override fun close() = Unit
        }
        val scope: NativeScope = ledger.scope(keepAlive)
        val secret: MemorySegment = scope.secret(byteArrayOf(9, 9, 9, 9))
        assertContentEquals(byteArrayOf(9, 9, 9, 9), secret.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
        scope.own("first") { order.add("release $it") }
        scope.own("second") { order.add("release $it") }

        scope.close()

        assertEquals(listOf("release second", "release first"), order)
        assertContentEquals(ByteArray(4), secret.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), "the native copy of the key must be zero")
        assertEquals(2, ledger.acquired)
        assertEquals(0, ledger.outstanding)
    }

    @Test
    fun `a release that throws does not stop the others and its failure is rethrown with the rest suppressed`() {
        val ledger = NativeLedger()
        val released = mutableListOf<String>()
        val scope: NativeScope = ledger.scope()
        scope.own("a") { released.add(it) }
        scope.own("b") { throw IllegalStateException("b failed") }
        scope.own("c") { throw IllegalArgumentException("c failed") }

        val thrown: IllegalArgumentException = assertFailsWith { scope.close() }

        assertEquals(listOf("a"), released, "the release after the failing ones still ran")
        assertEquals("c failed", thrown.message)
        assertEquals(listOf("b failed"), thrown.suppressed.map { it.message })
        assertEquals(0, ledger.outstanding)
    }
}
