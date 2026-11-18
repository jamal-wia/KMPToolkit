package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DpapiCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Paths
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Suite W (plan 6.4): the Windows DPAPI FFM binding against the real crypt32, with `-Pkmptoolkit.secretstore=windows`.
 *
 * Run it in the logged-on user's interactive session (a scheduled task with `/it`), never in a key-based ssh logon: there every
 * DPAPI call fails with 1312 (`ERROR_NO_SUCH_LOGON_SESSION`), which this suite reports as "environment cannot run it" (a skip, or a
 * failure with `-Pkmptoolkit.secretstore.required=true`). DPAPI keeps no state, so nothing needs cleaning up.
 */
class FfmDpapiRealStoreTest {

    private val ledger: NativeLedger = NativeLedger()
    private lateinit var calls: DpapiCalls

    @BeforeTest
    fun setUp() {
        RealStoreGate.require("windows")
        calls = FfmNativeKeyStoreBinding(Foreign.system(), ledger, FfmOs.WINDOWS).windows()
        val probe: RawResult<ByteArray> = calls.protect(byteArrayOf(1), null)
        if (probe is RawResult.Failure && probe.status.code == ERROR_NO_SUCH_LOGON_SESSION) {
            RealStoreGate.environmentUnavailable("windows", "DPAPI answers 1312: run the suite in the interactive session (schtasks /it)")
        }
        assertIs<RawResult.Success<ByteArray>>(probe, "the DPAPI probe round trip must succeed in an interactive session")
    }

    @AfterTest
    fun tearDown() {
        if (::calls.isInitialized) assertEquals(0, ledger.outstanding, "every LocalFree buffer must be freed")
    }

    private fun key(seed: Long): ByteArray = ByteArray(32).also { Random(seed).nextBytes(it) }

    private fun entropy(): ByteArray = "kmptoolkit-storage:test.kmptoolkit.securestorage".toByteArray(Charsets.UTF_8)

    @Test
    fun `protect then unprotect returns the key and the blob is not the key`() {
        val key: ByteArray = key(1L)
        val blob: ByteArray = assertIs<RawResult.Success<ByteArray>>(calls.protect(key, entropy())).value

        assertTrue(blob.isNotEmpty() && !blob.contentEquals(key))
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray>>(calls.unprotect(blob, entropy())).value)
        assertTrue(ledger.acquired >= 2, "control: both calls had an output to free (${ledger.acquired})")
    }

    @Test
    fun `a different entropy cannot unprotect the blob`() {
        val blob: ByteArray = assertIs<RawResult.Success<ByteArray>>(calls.protect(key(2L), entropy())).value

        val other: RawResult<ByteArray> = calls.unprotect(blob, "another store".toByteArray(Charsets.UTF_8))
        assertNotEquals(0, assertIs<RawResult.Failure>(other).status.code)
        assertTrue(assertIs<RawResult.Failure>(calls.unprotect(blob, null)).status.code != 0)
    }

    @Test
    fun `a corrupted or truncated blob fails with a real status and frees what it allocated`() {
        val blob: ByteArray = assertIs<RawResult.Success<ByteArray>>(calls.protect(key(3L), entropy())).value
        val flipped: ByteArray = blob.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }

        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(flipped, entropy())).status.code)
        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(blob.copyOf(blob.size / 2), entropy())).status.code)
        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(ByteArray(0), entropy())).status.code)
    }

    @Test
    fun `one thousand operations leave no handle and no buffer behind`() {
        val key: ByteArray = key(4L)
        val before: Long = processHandleCount()

        repeat(500) {
            val blob: ByteArray = assertIs<RawResult.Success<ByteArray>>(calls.protect(key, entropy())).value
            assertContentEquals(key, assertIs<RawResult.Success<ByteArray>>(calls.unprotect(blob, entropy())).value)
        }

        assertEquals(0, ledger.outstanding, "leaked LocalFree buffers")
        assertEquals(before, processHandleCount(), "leaked process handles")
    }

    /** `GetProcessHandleCount` of this process through the test's own FFM call (plan T17). */
    private fun processHandleCount(): Long {
        val linker: Linker = Linker.nativeLinker()
        val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup(
            Paths.get(FfmDpapi.defaultSystemRoot(), "System32", "kernel32.dll").toString(), java.lang.foreign.Arena.global(),
        )
        val currentProcess = linker.downcallHandle(kernel32.find("GetCurrentProcess").get(), FunctionDescriptor.of(ValueLayout.ADDRESS))
        val count = linker.downcallHandle(
            kernel32.find("GetProcessHandleCount").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        java.lang.foreign.Arena.ofConfined().use { arena: java.lang.foreign.Arena ->
            val out: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
            val process: MemorySegment = currentProcess.invokeWithArguments() as MemorySegment
            check(count.invokeWithArguments(process, out) as Int != 0) { "GetProcessHandleCount failed" }
            return out.get(ValueLayout.JAVA_INT, 0L).toLong() and 0xFFFFFFFFL
        }
    }

    private companion object {
        const val ERROR_NO_SUCH_LOGON_SESSION: Int = 1312
    }
}
