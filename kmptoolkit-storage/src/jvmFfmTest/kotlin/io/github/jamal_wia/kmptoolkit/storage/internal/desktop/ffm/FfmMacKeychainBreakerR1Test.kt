package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sto-ffm (STO-7): what the macOS FFM binding leaves in native memory, and its boundaries.
 *
 * Contract (plan 3.5, seam KDoc): "every raw call frees every operating-system-allocated output before returning ... and
 * zeroes its native copy of the key", read with the accepted deviation P-1 (breaker round 2 upheld the executor's dispute; the
 * lead records it in decisions.md): the `CFData` that `SecItemCopyMatching` returns belongs to the Security framework and is
 * copied out and released, never written to. `CFDataGetBytePtr` is a read-only pointer by its API contract, the retain count
 * cannot prove who owns the bytes, and on macOS 27.2 (measured in round 2) libmalloc already zeroes the freed 96-112 byte block
 * that holds a key-sized `CFData` inline, so a write would add risk and no protection. The native copies the binding makes itself
 * (the add buffer behind `CFDataCreateWithBytesNoCopy`) are wiped by [NativeScope] (pinned in FfmLoadAndScopeTest).
 * Runs against [FakeSecurityFramework], on any JDK 25 machine.
 *
 * Mutation by reading: zeroing (or otherwise writing into) the returned `CFData` before its `CFRelease` turns the first test red;
 * returning a view of the native bytes instead of a copy turns it red too (the release overwrites them).
 */
class FfmMacKeychainBreakerR1Test {

    private val fake: FakeSecurityFramework = FakeSecurityFramework()
    private val stubs: Arena = Arena.ofShared()

    /** The bytes every `CFData` held at the moment it was released (only the fake's returned data has bytes). */
    val bytesAtRelease: MutableList<ByteArray> = CopyOnWriteArrayList()

    private val releaseStub: MemorySegment = Linker.nativeLinker().upcallStub(
        MethodHandles.lookup().findVirtual(
            FfmMacKeychainBreakerR1Test::class.java, "recordingRelease",
            MethodType.methodType(Void.TYPE, MemorySegment::class.java),
        ).bindTo(this),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), stubs,
    )

    private val lookup: SymbolLookup = SymbolLookup { name: String ->
        when (name) {
            "CFRelease" -> Optional.of(releaseStub)
            else -> fake.lookup.find(name)
        }
    }
    private val ledger: NativeLedger = NativeLedger()
    private val calls: FfmMacKeychain = FfmMacKeychain(Foreign({ lookup }, Linker.nativeLinker()), ledger)

    @AfterTest
    fun tearDown() {
        fake.close()
        stubs.close()
    }

    @Suppress("unused")
    fun recordingRelease(cf: MemorySegment) {
        val length: Long = fake.cfDataGetLength(cf)
        if (length > 0L) {
            val bytes: MemorySegment = fake.cfDataGetBytePtr(cf).reinterpret(length)
            bytesAtRelease.add(bytes.toArray(ValueLayout.JAVA_BYTE))
            // What a freed block may hold next (libmalloc zero-on-free or a reuse): a key the caller got must not depend on it.
            bytes.fill(FREED_PATTERN)
        }
        fake.cfRelease(cf)
    }

    private fun assertNothingHeld(message: String) {
        assertEquals(emptyMap(), fake.live, "$message: the OS still holds CF objects")
        assertEquals(0, ledger.outstanding, "$message: the ledger says objects are still held")
        assertTrue(fake.badReleases.isEmpty(), "$message: double release ${fake.badReleases}")
    }

    @Test
    fun `the CFData that SecItemCopyMatching returned is released once, never written to, and the caller's key is an independent copy`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        fake.storedSecret = key

        val read: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = false)

        assertEquals(1, bytesAtRelease.size, "control: the returned CFData was released exactly once, with its bytes observed")
        assertContentEquals(key, bytesAtRelease.single(), "P-1: the binding never writes into the CFData the Security framework returned")
        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(read).value, "the caller's key must not alias the released native bytes")
        assertNothingHeld("copy")
    }

    @Test
    fun `a stored secret larger than the bound is refused, released and nothing is copied`() {
        fake.storedSecret = ByteArray((1 shl 20) + 1) { 5 }

        val read: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = true)

        assertEquals(-50, assertIs<RawResult.Failure>(read).status.code)
        assertEquals(1, bytesAtRelease.size, "control: the oversized CFData existed and was released")
        assertNothingHeld("oversized")
    }

    @Test
    fun `an empty secret round trips through add and copy without a native buffer of size zero breaking anything`() {
        fake.storedSecret = ByteArray(0)

        assertEquals(0, calls.addGenericPassword("svc", "acct", "label", ByteArray(0), allowUi = false))
        assertContentEquals(ByteArray(0), fake.secretSeenByOs, "control: the OS saw an empty CFData")
        val read: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = false)

        assertContentEquals(ByteArray(0), assertIs<RawResult.Success<ByteArray?>>(read).value)
        assertNothingHeld("empty")
    }

    private companion object {
        const val FREED_PATTERN: Byte = 0x55
    }
}
