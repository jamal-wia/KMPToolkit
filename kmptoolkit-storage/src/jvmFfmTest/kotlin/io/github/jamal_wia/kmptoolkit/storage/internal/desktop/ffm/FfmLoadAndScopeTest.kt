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

    /** libsecret 0.20.1 added the backend pair; a libsecret without it still works and has no portal backend. */
    private val optionalSymbols: Set<String> = setOf("secret_backend_get", "secret_backend_get_finish")

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
    fun `every symbol the binding asks for exists in the real libraries, and only the backend pair is optional`() {
        val real: Set<String> = RealLibraryExports.all("0.21.8.2")
        val everything = RecordingLookup(known = real)

        // Control: against the complete real table the binding loads (the backend question answers without native code only when
        // the symbols are absent, so the load itself is observed through the names it asked for).
        FfmSecretService(foreignOf { everything }, NativeLedger())

        val invented: List<String> = everything.asked.distinct().filter { it !in real }
        assertEquals(emptyList(), invented, "symbols the binding asks for that libsecret, glib, gobject and gio do not export")
        assertTrue(everything.asked.distinct().size >= 25, "control: the binding resolved its symbols (${everything.asked.distinct()})")

        for (missing: String in everything.asked.distinct()) {
            val service = FfmSecretService(foreignOf { RecordingLookup(real - missing) }, NativeLedger())
            // backendIsPortal() runs no native code when the backend pair is absent, so it tells a loaded binding from a missing one.
            val answer: RawResult<Boolean> = service.backendIsPortal()
            if (missing in optionalSymbols) {
                assertEquals(false, assertIs<RawResult.Success<Boolean>>(answer, "missing $missing is optional").value, "missing $missing")
            } else {
                assertTrue(assertIs<RawResult.Failure>(answer, "missing $missing must be SYMBOL_MISSING").status.isSymbolMissing, "missing $missing")
            }
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
