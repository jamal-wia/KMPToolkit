package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.LastErrorException
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What one fake DPAPI call did, so a test can see the flags, the entropy and what was handed back to the OS. */
internal class DpapiCall(val protect: Boolean, val input: ByteArray, val entropy: ByteArray?, val flags: Int, val description: Pointer?, val prompt: Pointer?)

/**
 * A scriptable crypt32 and kernel32 with real `DATA_BLOB` semantics: the output blob points at memory the fake "allocates",
 * and only `LocalFree` returns it. [allocations] minus [freed] is what a call leaked.
 */
internal class FakeWindowsLibraries {
    val calls: CopyOnWriteArrayList<DpapiCall> = CopyOnWriteArrayList()
    private val allocations: ConcurrentHashMap<Long, Memory> = ConcurrentHashMap()
    val freedWiped: CopyOnWriteArrayList<Boolean> = CopyOnWriteArrayList()
    val allocated: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
    val liveAllocations: Int get() = allocations.size

    /** `null` runs the transformation; a number makes the call fail with that last error; see also [staleErrorButSucceeds]. */
    @Volatile var failWith: Int? = null
    @Volatile var failWithoutError: Boolean = false
    @Volatile var staleErrorButSucceeds: Int? = null
    @Volatile var allocateThenFail: Boolean = false
    @Volatile var throwLinkage: Throwable? = null

    private fun run(protect: Boolean, dataIn: Pointer, description: Pointer?, entropy: Pointer?, prompt: Pointer?, flags: Int, dataOut: Pointer): Boolean {
        throwLinkage?.let { throw it }
        calls.add(DpapiCall(protect, readBlob(dataIn)!!, readBlob(entropy), flags, description, prompt))
        if (allocateThenFail) {
            allocate(dataOut, ByteArray(0))
            throw LastErrorException(13)
        }
        failWith?.let { throw LastErrorException(it) }
        if (failWithoutError) return false
        val input: ByteArray = readBlob(dataIn)!!
        val transformed = ByteArray(input.size) { (input[it].toInt() xor 0x5A).toByte() }
        allocate(dataOut, transformed)
        staleErrorButSucceeds?.let { throw LastErrorException(it) }
        return true
    }

    private fun allocate(dataOut: Pointer, bytes: ByteArray) {
        val memory = Memory(maxOf(1L, bytes.size.toLong()))
        memory.write(0L, bytes, 0, bytes.size)
        allocations[Pointer.nativeValue(memory)] = memory
        allocated.incrementAndGet()
        dataOut.setInt(0L, bytes.size)
        dataOut.setPointer(Native.POINTER_SIZE.toLong(), memory)
    }

    private fun readBlob(blob: Pointer?): ByteArray? {
        if (blob == null) return null
        val length: Int = blob.getInt(0L)
        return blob.getPointer(Native.POINTER_SIZE.toLong()).getByteArray(0L, length)
    }

    val crypt32: Crypt32Api = object : Crypt32Api {
        override fun CryptProtectData(dataIn: Pointer, description: Pointer?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, dataOut: Pointer): Boolean =
            run(true, dataIn, description, entropy, prompt, flags, dataOut)

        override fun CryptUnprotectData(dataIn: Pointer, descriptionOut: Pointer?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, dataOut: Pointer): Boolean =
            run(false, dataIn, descriptionOut, entropy, prompt, flags, dataOut)
    }

    val kernel32: Kernel32Api = object : Kernel32Api {
        override fun LocalFree(memory: Pointer?): Pointer? {
            val block: Memory = allocations.remove(Pointer.nativeValue(memory)) ?: error("LocalFree of memory that was never allocated or is already freed")
            freedWiped.add(block.getByteArray(0L, block.size().toInt()).all { it == 0.toByte() })
            return null
        }
    }

    val platform: WindowsPlatform = WindowsPlatform(crypt32, kernel32)
}

/** The Windows call object over the scripted DLLs: flags, entropy, error capture, and what it zeroes and frees. */
class DpapiCallsTest {

    private val key: ByteArray = ByteArray(32) { (it + 7).toByte() }
    private fun transformed(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }

    private fun calls(fake: FakeWindowsLibraries, scratch: RecordingScratch): JnaDpapiCalls = JnaDpapiCalls(lazyOf(fake.platform), scratch)

    @Test
    fun `protect never shows a dialog, passes the entropy, and zeroes and frees every native copy`() {
        val fake = FakeWindowsLibraries()
        val scratch = RecordingScratch()
        val entropy: ByteArray = "kmptoolkit-storage:svc".toByteArray()
        val result: RawResult<ByteArray> = calls(fake, scratch).protect(key, entropy)

        assertContentEquals(transformed(key), assertIs<RawResult.Success<ByteArray>>(result).value)
        val call: DpapiCall = fake.calls.single()
        assertEquals(JnaDpapiCalls.CRYPTPROTECT_UI_FORBIDDEN, call.flags and JnaDpapiCalls.CRYPTPROTECT_UI_FORBIDDEN, "CRYPTPROTECT_UI_FORBIDDEN is always set")
        assertEquals(JnaDpapiCalls.CRYPTPROTECT_UI_FORBIDDEN, call.flags, "and it is the only flag: never LOCAL_MACHINE")
        assertContentEquals(key, call.input)
        assertContentEquals(entropy, call.entropy)
        assertNull(call.prompt, "no prompt structure")
        assertEquals(1, fake.allocated.get(), "control: the OS allocated an output blob")
        assertEquals(0, fake.liveAllocations, "the output blob was returned to LocalFree")
        assertEquals(listOf(true), fake.freedWiped, "the output was zeroed before LocalFree")
        scratch.assertAllFreedAndWiped("protect")
        assertTrue(scratch.blocks.size >= 4, "input bytes, input blob, entropy bytes and blob, output blob")
    }

    @Test
    fun `unprotect without entropy passes no entropy blob and round trips`() {
        val fake = FakeWindowsLibraries()
        val scratch = RecordingScratch()
        val protectedBlob: ByteArray = assertIs<RawResult.Success<ByteArray>>(calls(fake, scratch).protect(key, null)).value
        val back: RawResult<ByteArray> = calls(fake, scratch).unprotect(protectedBlob, null)
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray>>(back).value)
        assertNull(fake.calls[0].entropy)
        assertNull(fake.calls[1].entropy)
        assertFalse(fake.calls[1].protect)
        assertEquals(0, fake.liveAllocations)
        assertEquals(listOf(true, true), fake.freedWiped)
        scratch.assertAllFreedAndWiped("round trip")
    }

    @Test
    fun `a failure reports GetLastError and frees nothing it never got`() {
        for (code: Int in listOf(13, 1312, 0x8009000B.toInt(), 5)) {
            val fake = FakeWindowsLibraries()
            fake.failWith = code
            val scratch = RecordingScratch()
            val result: RawResult<ByteArray> = calls(fake, scratch).unprotect(byteArrayOf(1, 2, 3), null)
            assertEquals(RawStatus(code), assertIs<RawResult.Failure>(result).status, "code $code")
            assertEquals(0, fake.allocated.get())
            assertTrue(fake.freedWiped.isEmpty(), "no LocalFree of a blob that was never allocated")
            scratch.assertAllFreedAndWiped("failure $code")
        }
    }

    @Test
    fun `a failure that left an empty allocation behind still frees it`() {
        val fake = FakeWindowsLibraries()
        fake.allocateThenFail = true
        val scratch = RecordingScratch()
        val result = calls(fake, scratch).protect(key, null)
        assertEquals(RawStatus(13), assertIs<RawResult.Failure>(result).status)
        assertEquals(1, fake.allocated.get(), "control: something was allocated")
        assertEquals(0, fake.liveAllocations)
        assertEquals(listOf(true), fake.freedWiped)
        scratch.assertAllFreedAndWiped("allocate then fail")
    }

    @Test
    fun `a failure with no recorded error is still a non-zero status`() {
        val fake = FakeWindowsLibraries()
        fake.failWithoutError = true
        val status: RawStatus = assertIs<RawResult.Failure>(calls(fake, RecordingScratch()).protect(key, null)).status
        assertEquals(JnaDpapiCalls.ERROR_GEN_FAILURE, status.code)
        assertFalse(status.isSuccess, "a failure never looks like success")
    }

    @Test
    fun `a stale last error next to a produced blob is a success, and the blob is freed`() {
        val fake = FakeWindowsLibraries()
        fake.staleErrorButSucceeds = 183
        val result = calls(fake, RecordingScratch()).protect(key, null)
        assertContentEquals(transformed(key), assertIs<RawResult.Success<ByteArray>>(result).value)
        assertEquals(0, fake.liveAllocations)
    }

    @Test
    fun `an empty input is handled without a crash`() {
        val fake = FakeWindowsLibraries()
        val scratch = RecordingScratch()
        val result = calls(fake, scratch).protect(ByteArray(0), null)
        assertContentEquals(ByteArray(0), assertIs<RawResult.Success<ByteArray>>(result).value)
        assertEquals(0, fake.liveAllocations)
        scratch.assertAllFreedAndWiped("empty")
    }

    @Test
    fun `a link error from the DLL leaves no native block behind`() {
        val fake = FakeWindowsLibraries()
        fake.throwLinkage = UnsatisfiedLinkError("crypt32")
        val scratch = RecordingScratch()
        assertFailsWith<UnsatisfiedLinkError> { calls(fake, scratch).protect(key, "e".toByteArray()) }
        scratch.assertAllFreedAndWiped("link error")
        assertEquals(0, fake.liveAllocations)
    }

    @Test
    fun `a thousand operations from many threads leak nothing (seed 5102)`() {
        val seed = 5102
        val fake = FakeWindowsLibraries()
        val scratch = RecordingScratch()
        val calls: JnaDpapiCalls = calls(fake, scratch)
        val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val start = CountDownLatch(1)
        val threads: List<Thread> = (0 until 8).map { worker: Int ->
            Thread {
                val random = Random(seed * 1000 + worker)
                start.await()
                repeat(125) {
                    val data = ByteArray(random.nextInt(0, 64)) { random.nextInt().toByte() }
                    val entropy: ByteArray? = if (random.nextBoolean()) null else ByteArray(random.nextInt(1, 8))
                    try {
                        val sealed: RawResult<ByteArray> = calls.protect(data, entropy)
                        val opened: RawResult<ByteArray> = calls.unprotect((sealed as RawResult.Success).value, entropy)
                        if (!data.contentEquals((opened as RawResult.Success).value)) problems.add("seed=$seed worker=$worker: round trip differs")
                    } catch (error: Throwable) {
                        problems.add("seed=$seed worker=$worker: $error")
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        for (thread: Thread in threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60))
            assertFalse(thread.isAlive, "seed=$seed: a worker hung")
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertEquals(2000, fake.allocated.get(), "seed=$seed: control")
        assertEquals(0, fake.liveAllocations, "seed=$seed")
        assertTrue(fake.freedWiped.all { it }, "seed=$seed")
        assertEquals(scratch.allocated, scratch.closed.get(), "seed=$seed")
        assertTrue(scratch.blocks.all { it.wipedAtClose }, "seed=$seed")
    }
}
