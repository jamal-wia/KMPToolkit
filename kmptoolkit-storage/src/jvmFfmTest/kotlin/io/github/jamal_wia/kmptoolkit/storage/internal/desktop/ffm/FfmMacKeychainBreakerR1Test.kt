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
 * zeroes its native copy of the key". In `copyGenericPassword` the only native copy of the key the call holds is the `CFData`
 * that `SecItemCopyMatching` returned to it (+1, the caller owns it); the Windows binding zeroes the analogous DPAPI output
 * before `LocalFree`, and libsecret wipes its `SecretValue`. The fake answers `CFGetRetainCount` = 1 (the binding is the only
 * owner, so a fix may zero only a buffer nobody else holds). Runs against [FakeSecurityFramework], on any JDK 25 machine.
 *
 * Mutation by reading: removing a wipe of the returned `CFData` before its `CFRelease` turns the first test red (it is red on
 * df9b50ac, where the bytes are copied out and released unchanged).
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
    private val retainCountStub: MemorySegment = Linker.nativeLinker().upcallStub(
        MethodHandles.lookup().findVirtual(
            FfmMacKeychainBreakerR1Test::class.java, "soleOwner",
            MethodType.methodType(Long::class.java, MemorySegment::class.java),
        ).bindTo(this),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), stubs,
    )

    private val lookup: SymbolLookup = SymbolLookup { name: String ->
        when (name) {
            "CFRelease" -> Optional.of(releaseStub)
            "CFGetRetainCount" -> Optional.of(retainCountStub)
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
        if (length > 0L) bytesAtRelease.add(fake.cfDataGetBytePtr(cf).reinterpret(length).toArray(ValueLayout.JAVA_BYTE))
        fake.cfRelease(cf)
    }

    @Suppress("unused")
    fun soleOwner(cf: MemorySegment): Long = 1L

    private fun assertNothingHeld(message: String) {
        assertEquals(emptyMap(), fake.live, "$message: the OS still holds CF objects")
        assertEquals(0, ledger.outstanding, "$message: the ledger says objects are still held")
        assertTrue(fake.badReleases.isEmpty(), "$message: double release ${fake.badReleases}")
    }

    @Test
    fun `the CFData holding the key that SecItemCopyMatching returned is zero when the binding releases it`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        fake.storedSecret = key

        val read: RawResult<ByteArray?> = calls.copyGenericPassword("svc", "acct", allowUi = false)

        assertContentEquals(key, assertIs<RawResult.Success<ByteArray?>>(read).value, "control: the caller got the key")
        assertEquals(1, bytesAtRelease.size, "control: the returned CFData was released once, with its bytes observed")
        assertContentEquals(ByteArray(32), bytesAtRelease.single(), "the native copy of the key must be zeroed before CFRelease")
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
}
