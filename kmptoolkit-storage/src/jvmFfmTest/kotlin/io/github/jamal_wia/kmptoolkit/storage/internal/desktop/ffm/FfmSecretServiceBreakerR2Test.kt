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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Breaker round 2 of group sto-ffm (STO-7): attacks on the new libsecret backend question (a private thread-default `GMainContext`
 * iterated until the `secret_backend_get` callback fires, round-2 fix of B1).
 *
 * Contract (FfmSecretService KDoc, plan 3.5): the callback must never let an exception escape the upcall (that ends the JVM); a failure
 * inside it is recorded and rethrown by the caller outside the upcall; the push is popped and the context released on every path;
 * a failed backend question makes `defaultCollectionLocked` a failure without asking the Secret Service. Runs against [FakeGio].
 *
 * Mutation by reading: dropping the `catch (Throwable)` in `BackendWait.onReady` ends the test JVM (red by crash); setting `fired`
 * only on success makes the binding spin on a context nothing can wake (red at the hang bound, and FakeGio records the blocking
 * iteration); dropping the rethrow answers a `Failure` and the first test is red; registering the pop after the stub creation or
 * skipping the unref leaves a push or a context behind; asking the collection after a failed backend question turns the second red.
 */
class FfmSecretServiceBreakerR2Test {

    private val gio: FakeGio = FakeGio()
    private val ledger: NativeLedger = NativeLedger()
    private val stubs: Arena = Arena.ofShared()

    @AfterTest
    fun tearDown() {
        gio.close()
        stubs.close()
    }

    private fun assertClean(message: String) {
        assertEquals(emptyList(), gio.violations, "$message: the binding breached the GIO contract")
        assertEquals(emptyList(), gio.threadDefault, "$message: a thread-default push was never popped")
        assertEquals(emptySet(), gio.liveContexts, "$message: a main context leaked")
        assertEquals(0, ledger.outstanding, "$message: the ledger says objects are still held")
    }

    /** Runs [block] on a thread of its own with a hang bound (a spin or a blocking wait is the failure, never a stuck test run). */
    private fun <T> bounded(block: () -> T): Result<T> {
        val outcome: AtomicReference<Result<T>?> = AtomicReference(null)
        val worker = Thread({ outcome.set(runCatching(block)) }, "sto-ffm-breaker-r2")
        worker.isDaemon = true
        worker.start()
        worker.join(TimeUnit.SECONDS.toMillis(HANG_BOUND_SECONDS))
        if (worker.isAlive) {
            fail("the backend question did not return within $HANG_BOUND_SECONDS s: ${worker.stackTrace.take(8).joinToString(" <- ")}")
        }
        return assertNotNull(outcome.get(), "the worker ended without an outcome")
    }

    /**
     * `secret_backend_get` that completes on a foreign thread (a GIO misuse that real GTask never does, used here as the one
     * deterministic way to make the Java code inside the upcall throw): the callback runs on a new thread, where the binding's
     * finish call cannot use the call's confined arena, and the stand-in waits for that thread before it returns.
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    fun foreignThreadGet(flags: Int, cancellable: MemorySegment, callback: MemorySegment, userData: MemorySegment) {
        val invoke = Linker.nativeLinker().downcallHandle(
            callback, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val deliverer = Thread({ invoke.invokeWithArguments(MemorySegment.NULL, MemorySegment.ofAddress(RESULT_ADDRESS), userData) }, "foreign-gtask")
        deliverer.start()
        deliverer.join(TimeUnit.SECONDS.toMillis(HANG_BOUND_SECONDS))
    }

    private val foreignGetStub: MemorySegment = Linker.nativeLinker().upcallStub(
        MethodHandles.lookup().findVirtual(
            FfmSecretServiceBreakerR2Test::class.java, "foreignThreadGet",
            MethodType.methodType(Void.TYPE, Int::class.java, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
        ).bindTo(this),
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        stubs,
    )

    @Test
    fun `an exception inside the backend callback is rethrown by the caller, the JVM survives and the context is popped and released`() {
        val lookup = SymbolLookup { name: String ->
            if (name == "secret_backend_get") gio.find(name).map { foreignGetStub } else gio.find(name)
        }
        val service = FfmSecretService(Foreign({ lookup }, Linker.nativeLinker()), ledger)

        val outcome: Result<RawResult<Boolean>> = bounded { service.backendIsPortal() }

        val thrown: Throwable = outcome.exceptionOrNull()
            ?: fail("the failure inside the upcall must be rethrown by the caller, got ${outcome.getOrNull()}")
        assertIs<WrongThreadException>(thrown, "control: the recorded failure is the one the upcall met, rethrown unchanged")
        assertEquals(0, gio.finishCalls, "control: the finish call never reached libsecret")
        assertEquals(1, gio.contextsCreated, "control: the question had its private context")
        assertClean("upcall failure")

        // The binding stays usable: the next question over the plain fake is answered normally.
        val next: RawResult<Boolean> = FfmSecretService(Foreign({ gio }, Linker.nativeLinker()), ledger).backendIsPortal()
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(next).value)
        assertClean("after the upcall failure")
    }

    @Test
    fun `a failed backend question makes defaultCollectionLocked a failure without asking the Secret Service`() {
        gio.finishWithError = true
        gio.defaultCollection = FakeGio.DefaultCollection.UNLOCKED

        val answer: RawResult<Boolean> = bounded { FfmSecretService(Foreign({ gio }, Linker.nativeLinker()), ledger).defaultCollectionLocked() }
            .getOrThrow()

        assertEquals(FakeGio.ERROR_CODE, assertIs<RawResult.Failure>(answer).status.code, "the backend failure is the answer")
        assertEquals(0, gio.collectionQueries, "a failed backend question must not fall through to the collection query")
        assertEquals(1, gio.freedErrors.size, "control: the GError existed and was freed once")
        assertClean("failed backend question")
    }

    @Test
    fun `a storm of late callbacks with duplicate deliveries alternating with failures leaves nothing behind (seed 2302)`() {
        val random = java.util.Random(SEED)
        repeat(STORM) { index: Int ->
            gio.fireAtIteration = random.nextInt(9)
            gio.duplicateDelivery = random.nextBoolean()
            gio.finishWithError = random.nextInt(4) == 0
            gio.backendTypeName = if (random.nextBoolean()) "SecretFileBackend" else "SecretService"
            val answer: RawResult<Boolean> = FfmSecretService(Foreign({ gio }, Linker.nativeLinker()), ledger).backendIsPortal()
            if (gio.finishWithError) {
                assertIs<RawResult.Failure>(answer, "seed=$SEED index=$index")
            } else {
                assertEquals(gio.backendTypeName == "SecretFileBackend", assertIs<RawResult.Success<Boolean>>(answer).value, "seed=$SEED index=$index")
            }
        }
        assertEquals(STORM, gio.finishCalls, "seed=$SEED: every request was finished exactly once, duplicates ignored")
        assertTrue(gio.freedErrors.isNotEmpty(), "seed=$SEED control: the storm met failures")
        assertClean("storm seed=$SEED")
    }

    private companion object {
        const val HANG_BOUND_SECONDS: Long = 30L
        const val RESULT_ADDRESS: Long = 0x2000L
        const val SEED: Long = 2302L
        const val STORM: Int = 500
    }
}
