package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Binding selection (plan 4.2, T5 selector rows, T9 selection rows that need no second JVM): every branch of the
 * rule on any machine, the failure mapping of step 5, and "once per process".
 */
class BindingSelectorTest {

    private val ffm = FakeNativeKeyStoreBinding()
    private val jna = FakeNativeKeyStoreBinding()

    private fun environment(
        os: String = "Mac OS X",
        feature: Int = 25,
        loadFfm: () -> NativeKeyStoreBinding? = { ffm },
        loadJna: () -> NativeKeyStoreBinding? = { jna },
    ): SelectorEnvironment = SelectorEnvironment(os, feature, loadFfm, loadJna)

    private fun unsupportedReason(selection: BindingSelection): UnsupportedReason =
        (selection as BindingSelection.Unsupported).error.reason

    @Test
    fun `os name maps to the three desktop systems and nothing else`() {
        assertEquals(DesktopOs.MACOS, DesktopOs.fromOsName("Mac OS X"))
        assertEquals(DesktopOs.MACOS, DesktopOs.fromOsName("Darwin"))
        assertEquals(DesktopOs.WINDOWS, DesktopOs.fromOsName("Windows 11"))
        assertEquals(DesktopOs.WINDOWS, DesktopOs.fromOsName("Windows Server 2022"))
        assertEquals(DesktopOs.LINUX, DesktopOs.fromOsName("Linux"))
        for (other: String in listOf("FreeBSD", "SunOS", "AIX", "", "OS/2", "Android")) {
            assertNull(DesktopOs.fromOsName(other), "'$other' is not a desktop OS the store serves")
        }
    }

    @Test
    fun `an unsupported operating system is Unsupported UNSUPPORTED_OS and loads nothing`() {
        val loads = AtomicInteger()
        val selector = BindingSelector(
            environment(os = "FreeBSD", loadFfm = { loads.incrementAndGet(); ffm }, loadJna = { loads.incrementAndGet(); jna }),
        )

        assertEquals(UnsupportedReason.UNSUPPORTED_OS, unsupportedReason(selector.select()))
        assertEquals(0, loads.get(), "no binding may be loaded for an unsupported OS")
    }

    @Test
    fun `runtime 22 or newer serves FFM and ignores a JNA artifact on the classpath`() {
        val jnaLoads = AtomicInteger()
        for (feature: Int in listOf(22, 24, 25, 27)) {
            val selector = BindingSelector(environment(feature = feature, loadJna = { jnaLoads.incrementAndGet(); jna }))
            val selection: BindingSelection.Selected = assertIs(selector.select())
            assertSame(ffm, selection.binding, "runtime $feature")
            assertEquals(BindingKind.FFM, selection.kind)
            assertEquals(DesktopOs.MACOS, selection.os)
        }
        assertEquals(0, jnaLoads.get(), "the JNA class must not even be loaded on a runtime that serves FFM")
    }

    @Test
    fun `runtime 21 serves JNA when the artifact is present and UNSUPPORTED_RUNTIME when it is not`() {
        val ffmLoads = AtomicInteger()
        val with = BindingSelector(environment(feature = 21, loadFfm = { ffmLoads.incrementAndGet(); ffm }))
        val selected: BindingSelection.Selected = assertIs(with.select())
        assertSame(jna, selected.binding)
        assertEquals(BindingKind.JNA, selected.kind)

        val without = BindingSelector(environment(feature = 21, loadJna = { null }))
        assertEquals(UnsupportedReason.UNSUPPORTED_RUNTIME, unsupportedReason(without.select()))
        assertEquals(0, ffmLoads.get(), "FFM must never be tried below 22")
    }

    @Test
    fun `a missing FFM class on a runtime that should serve FFM is NATIVE_ACCESS_UNAVAILABLE`() {
        val selector = BindingSelector(environment(feature = 25, loadFfm = { null }))

        assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, unsupportedReason(selector.select()))
    }

    @Test
    fun `every load failure of step 5 is NATIVE_ACCESS_UNAVAILABLE with the failure as cause`() {
        val failures: List<Throwable> = listOf(
            IllegalCallerException("--illegal-native-access=deny"),
            UnsatisfiedLinkError("jnidispatch from a noexec temp directory"),
            SecurityException("a security manager"),
            NoSuchMethodError("a JDK 22-24 runtime"),
            NoClassDefFoundError("com/sun/jna/Native"),
            ExceptionInInitializerError(RuntimeException("init")),
            IllegalStateException("a constructor that throws"),
        )
        for (failure: Throwable in failures) {
            for (feature: Int in listOf(25, 21)) {
                val selector = BindingSelector(environment(feature = feature, loadFfm = { throw failure }, loadJna = { throw failure }))
                val selection: BindingSelection.Unsupported = assertIs(selector.select(), "$failure on $feature")
                assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, selection.error.reason, "$failure on $feature")
                assertSame(failure, selection.error.cause)
            }
        }
    }

    @Test
    fun `a virtual machine error while loading is not swallowed`() {
        val selector = BindingSelector(environment(loadFfm = { throw OutOfMemoryError("test") }))

        assertFailsWith<OutOfMemoryError> { selector.select() }
    }

    @Test
    fun `the decision is made once even under a thread storm and a failure is kept, not retried`() {
        val loads = AtomicInteger()
        val ok = BindingSelector(environment(loadFfm = { loads.incrementAndGet(); ffm }))
        val failing = BindingSelector(environment(loadFfm = { loads.incrementAndGet(); throw UnsatisfiedLinkError("x") }))
        val go = CountDownLatch(1)
        val answers: CopyOnWriteArrayList<BindingSelection> = CopyOnWriteArrayList()
        val threads: List<Thread> = (0 until 16).map { index: Int ->
            thread(name = "select-$index") {
                assertTrue(go.await(60, TimeUnit.SECONDS))
                answers += (if (index % 2 == 0) ok else failing).select()
            }
        }
        go.countDown()
        threads.forEach { it.join(60_000) }

        assertTrue(threads.none { it.isAlive }, "a selector call is still blocked")
        assertEquals(2, loads.get(), "each selector loads exactly once")
        assertEquals(1, answers.filterIsInstance<BindingSelection.Selected>().map { it.binding }.toSet().size)
        assertEquals(8, answers.count { it is BindingSelection.Unsupported }, "eight callers asked the failing selector")
        val failed: Set<BindingSelection> = answers.filterIsInstance<BindingSelection.Unsupported>().toSet()
        assertEquals(1, failed.size, "every caller of the failing selector saw the same answer")
    }

    @Test
    fun `a binding class that throws in its constructor is unwrapped to its cause`() {
        val error: Throwable = assertFailsWith<UnsatisfiedLinkError> {
            SelectorEnvironment.loadByName(ThrowsLinkageError::class.java.name)
        }
        assertEquals("constructor", error.message)
    }

    @Test
    fun `a class name that does not exist loads as null and a class that is no binding fails loudly`() {
        assertNull(SelectorEnvironment.loadByName("io.github.jamal_wia.kmptoolkit.storage.jna.NoSuchBinding"))
        assertFailsWith<ClassCastException> { SelectorEnvironment.loadByName(NotABinding::class.java.name) }
        assertSame(BindingKind.FAKE, BindingKind.valueOf("FAKE"), "the fake kind exists for tests")
    }

    @Test
    fun `the fixed source hands out exactly the selection it was given`() {
        val selection = BindingSelection.Selected(ffm, DesktopOs.LINUX, BindingKind.FAKE)

        assertSame(selection, FixedBindingSource(selection).select())
    }

    /** A binding class whose constructor throws a linkage error, as `UnsatisfiedLinkError` does for a native library. */
    class ThrowsLinkageError : NativeKeyStoreBinding {
        init {
            throw UnsatisfiedLinkError("constructor")
        }

        override fun macOs(): MacKeychainCalls = error("unused")

        override fun windows(): DpapiCalls = error("unused")

        override fun linux(): SecretServiceCalls = error("unused")
    }

    /** A class with a public no-argument constructor that is not a binding. */
    class NotABinding
}
