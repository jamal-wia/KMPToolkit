package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The two kernel32 functions the handle-count loop needs; test only. */
internal interface HandleCountApi : Library {
    fun GetCurrentProcess(): Pointer
    fun GetProcessHandleCount(process: Pointer, count: IntByReference): Boolean
}

/**
 * Suite W of plan 6.4 over the real DPAPI, through this artifact's JNA binding. Opt-in:
 * `gradlew :kmptoolkit-storage-jna:jvmTest --tests *RealDpapiSuiteTest -Pkmptoolkit.secretstore=windows`
 * (add `-Pkmptoolkit.secretstore.required=true` to make a skip a failure).
 *
 * Run it in the logged-on user's interactive session (a scheduled task bound to it, or the remote-desktop session), never
 * in a key-based ssh logon: there no user credentials are loaded and every DPAPI call fails with 1312
 * (`ERROR_NO_SUCH_LOGON_SESSION`). DPAPI has no store of its own, so the suite creates nothing outside memory.
 */
class RealDpapiSuiteTest {

    private lateinit var calls: JnaDpapiCalls
    private val key: ByteArray = ByteArray(32) { (it * 5 + 2).toByte() }
    private val entropy: ByteArray = "kmptoolkit-storage:test.store".toByteArray()

    @BeforeTest
    fun setUp() {
        RealStoreGate.enter("windows")
        calls = JnaDpapiCalls(lazy(WindowsPlatform::load))
    }

    private fun protect(data: ByteArray, with: ByteArray?): ByteArray = assertIs<RawResult.Success<ByteArray>>(calls.protect(data, with)).value

    @Test
    fun `a key round trips under its entropy, and the blob is not the key`() {
        val blob: ByteArray = protect(key, entropy)
        assertFalse(blob.contentEquals(key))
        assertTrue(blob.size > key.size, "the DPAPI blob carries metadata")
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray>>(calls.unprotect(blob, entropy)).value)
    }

    @Test
    fun `another entropy, a corrupt blob and a truncated blob fail with a status and never return a key`() {
        val blob: ByteArray = protect(key, entropy)
        val wrongEntropy: RawResult<ByteArray> = calls.unprotect(blob, "other".toByteArray())
        assertNotEquals(0, assertIs<RawResult.Failure>(wrongEntropy).status.code)

        val corrupt: ByteArray = blob.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(corrupt, entropy)).status.code, "a corrupt blob: ERROR_INVALID_DATA or NTE_BAD_KEY_STATE")
        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(blob.copyOf(blob.size / 2), entropy)).status.code)
        assertNotEquals(0, assertIs<RawResult.Failure>(calls.unprotect(ByteArray(0), entropy)).status.code)
    }

    @Test
    fun `a one-byte throwaway round trip works, as the probe needs`() {
        assertContentEquals(byteArrayOf(1), assertIs<RawResult.Success<ByteArray>>(calls.unprotect(protect(byteArrayOf(1), null), null)).value)
    }

    @Test
    fun `a thousand operations leave the process handle count unchanged`() {
        val kernel: HandleCountApi = Native.load("kernel32", HandleCountApi::class.java)
        fun handles(): Int {
            val count = IntByReference()
            check(kernel.GetProcessHandleCount(kernel.GetCurrentProcess(), count)) { "GetProcessHandleCount failed" }
            return count.value
        }
        repeat(WARM_UP) { protect(key, entropy) }
        val before: Int = handles()
        assertTrue(before > 0, "control: the handle count is readable")
        repeat(1_000) { assertContentEquals(key, assertIs<RawResult.Success<ByteArray>>(calls.unprotect(protect(key, entropy), entropy)).value) }
        assertEquals(before, handles(), "2,000 DPAPI calls must not leak a handle")
    }

    private companion object {
        const val WARM_UP: Int = 50
    }
}
