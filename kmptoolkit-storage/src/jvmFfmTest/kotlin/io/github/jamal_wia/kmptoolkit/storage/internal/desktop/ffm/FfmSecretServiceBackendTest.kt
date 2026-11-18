package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawStatus
import java.lang.foreign.Linker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The backend and default-collection questions of the Linux FFM binding (plan 3.5, 4.7; sto-ffm round 2, B1 and M1), against
 * [FakeGio]: libsecret has no synchronous backend call, so the binding runs `secret_backend_get` on a private thread-default main
 * context and iterates it until the callback fires. The fake models the GIO contract and records every breach of it.
 *
 * Contract under test: `backendIsPortal` is `true` only for libsecret's file backend; a libsecret without the backend pair (0.19)
 * answers `false`; `defaultCollectionLocked` is `false` on the portal backend, `true` when no default collection exists (never
 * `false`: storing would prompt to create one), and the collection's `Locked` property otherwise; nothing is leaked and the
 * borrowed backend is never unreferenced.
 *
 * Mutation by reading: calling `g_object_unref` on the backend, popping before the push, finishing outside the callback, testing
 * `fired` only after a blocking iteration, or answering `false` for a missing collection each turn a test here red.
 */
class FfmSecretServiceBackendTest {

    private val gio: FakeGio = FakeGio()
    private val ledger: NativeLedger = NativeLedger()

    @AfterTest
    fun tearDown() {
        gio.close()
    }

    private fun serviceOver(fake: FakeGio = gio): FfmSecretService = FfmSecretService(Foreign({ fake }, Linker.nativeLinker()), ledger)

    private fun assertClean(fake: FakeGio = gio, message: String) {
        assertEquals(emptyList(), fake.violations, "$message: the binding breached the GIO contract")
        assertEquals(emptyList(), fake.threadDefault, "$message: a thread-default push was never popped")
        assertEquals(emptySet(), fake.liveContexts, "$message: a main context leaked")
        assertEquals(0, ledger.outstanding, "$message: the ledger says objects are still held")
    }

    private fun portal(service: FfmSecretService): Boolean = assertIs<RawResult.Success<Boolean>>(service.backendIsPortal()).value

    @Test
    fun `the file backend is the portal and the Secret Service is not, with the callback delivered inside the call`() {
        for ((typeName: String, expected: Boolean) in listOf("SecretFileBackend" to true, "SecretService" to false, "SomethingElse" to false)) {
            gio.backendTypeName = typeName

            assertEquals(expected, portal(serviceOver()), typeName)
        }

        assertEquals(3, gio.backendGetCalls, "control: libsecret was asked three times")
        assertEquals(3, gio.finishCalls, "control: every request was finished once, inside its callback")
        assertEquals(3, gio.contextsCreated, "control: every request had a private context")
        assertEquals(3, gio.iterations, "the drain pass after a fired callback is the only iteration of each question")
        assertClean(message = "types")
    }

    @Test
    fun `the context is iterated until the callback fires whatever spurious wake-ups come first`() {
        gio.fireAtIteration = 6
        gio.backendTypeName = "SecretFileBackend"

        assertTrue(portal(serviceOver()))

        assertEquals(7, gio.iterations, "six waiting iterations up to and including the delivery, plus the drain pass")
        assertEquals(1, gio.finishCalls)
        assertClean(message = "late callback")
    }

    @Test
    fun `a second delivery of the same callback is ignored`() {
        gio.duplicateDelivery = true

        assertEquals(false, portal(serviceOver()))

        assertEquals(1, gio.finishCalls, "the request is finished exactly once")
        assertClean(message = "duplicate")
    }

    @Test
    fun `a failed request is a typed failure and its GError is freed`() {
        gio.finishWithError = true

        val answer: RawResult<Boolean> = serviceOver().backendIsPortal()

        val status: RawStatus = assertIs<RawResult.Failure>(answer).status
        assertEquals(FakeGio.ERROR_CODE, status.code)
        assertEquals(FakeGio.ERROR_DOMAIN, status.domain)
        assertEquals(1, gio.freedErrors.size, "control: the error existed and was freed once")
        assertClean(message = "error")
    }

    @Test
    fun `a request that answers neither a backend nor an error is a failure, never a portal`() {
        gio.finishWithNothing = true

        val status: RawStatus = assertIs<RawResult.Failure>(serviceOver().backendIsPortal()).status

        assertEquals(0, status.code, "G_IO_ERROR_FAILED")
        assertEquals("g-io-error-quark", status.domain)
        assertClean(message = "nothing")
    }

    @Test
    fun `the backend that libsecret returns is borrowed and never unreferenced, however often it is asked`() {
        val service: FfmSecretService = serviceOver()

        repeat(5) { assertEquals(false, portal(service)) }

        // The fake records an unref of the backend as a violation; control that unrefs are observable at all: the collection is released.
        gio.defaultCollection = FakeGio.DefaultCollection.UNLOCKED
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(service.defaultCollectionLocked()).value)
        assertEquals(1, gio.collectionUnrefs, "control: an object this layer owns is unreferenced")
        assertClean(message = "borrowed")
    }

    @Test
    fun `a missing default collection is locked, because storing would have to create it and prompt`() {
        gio.defaultCollection = FakeGio.DefaultCollection.NONE

        val locked: Boolean = assertIs<RawResult.Success<Boolean>>(serviceOver().defaultCollectionLocked()).value

        assertTrue(locked, "no default collection must answer true, never false")
        assertEquals(1, gio.collectionQueries, "control: the collection was asked")
        assertClean(message = "no collection")
    }

    @Test
    fun `a locked and an unlocked default collection answer their Locked property`() {
        gio.defaultCollection = FakeGio.DefaultCollection.LOCKED
        assertEquals(true, assertIs<RawResult.Success<Boolean>>(serviceOver().defaultCollectionLocked()).value)
        gio.defaultCollection = FakeGio.DefaultCollection.UNLOCKED
        assertEquals(false, assertIs<RawResult.Success<Boolean>>(serviceOver().defaultCollectionLocked()).value)

        assertEquals(2, gio.collectionQueries)
        assertEquals(2, gio.collectionUnrefs, "both collection references were released")
        assertClean(message = "locked")
    }

    @Test
    fun `the portal backend has no locked collection and the Secret Service API is not touched`() {
        gio.backendTypeName = "SecretFileBackend"
        gio.defaultCollection = FakeGio.DefaultCollection.NONE

        assertEquals(false, assertIs<RawResult.Success<Boolean>>(serviceOver().defaultCollectionLocked()).value)

        assertEquals(0, gio.collectionQueries, "the collection of the Secret Service must not be asked on the portal")
        assertClean(message = "portal collection")
    }

    @Test
    fun `the collection is queried with no private context pushed, and a failed query is a typed failure`() {
        gio.collectionQueryError = true

        val answer: RawResult<Boolean> = serviceOver().defaultCollectionLocked()

        assertEquals(FakeGio.ERROR_CODE, assertIs<RawResult.Failure>(answer).status.code)
        assertEquals(0, gio.stackDepthAtCollectionQuery, "the backend question's context must be popped before the collection query")
        assertEquals(1, gio.freedErrors.size)
        assertClean(message = "collection error")
    }

    @Test
    fun `libsecret 0_19 has no backend pair, so it loads, has no portal, builds no context and treats a missing collection as locked`() {
        FakeGio(version = "0.19.1").use { old: FakeGio ->
            val service: FfmSecretService = serviceOver(old)

            assertEquals(false, portal(service), "no backend API means no portal backend")
            old.defaultCollection = FakeGio.DefaultCollection.NONE
            assertEquals(true, assertIs<RawResult.Success<Boolean>>(service.defaultCollectionLocked()).value)

            assertTrue("secret_backend_get" in old.asked, "control: the optional symbol was looked up")
            assertEquals(0, old.contextsCreated, "no main context for a question that needs none")
            assertEquals(1, old.collectionQueries)
            assertClean(old, "0.19.1")
        }
    }

    @Test
    fun `only one of the two backend symbols is as good as none and is not a broken library`() {
        for (absent: String in listOf("secret_backend_get", "secret_backend_get_finish")) {
            FakeGio(missing = setOf(absent)).use { half: FakeGio ->
                val answer: RawResult<Boolean> = serviceOver(half).backendIsPortal()

                assertEquals(false, assertIs<RawResult.Success<Boolean>>(answer).value, "without $absent")
                assertEquals(0, half.backendGetCalls, "a half pair is never called ($absent)")
                assertEquals(0, half.contextsCreated, absent)
            }
        }
    }

    @Test
    fun `a missing glib main-context function is a missing symbol, not a half binding`() {
        for (absent: String in listOf("g_main_context_new", "g_main_context_push_thread_default", "g_main_context_pop_thread_default", "g_main_context_unref", "g_main_context_iteration")) {
            FakeGio(missing = setOf(absent)).use { broken: FakeGio ->
                val status: RawStatus = assertIs<RawResult.Failure>(serviceOver(broken).backendIsPortal()).status

                assertTrue(status.isSymbolMissing, "without $absent")
            }
        }
    }

    @Test
    fun `two thousand questions on fresh and reused bindings leave no context, no error and no push behind`() {
        val reused: FfmSecretService = serviceOver()
        gio.fireAtIteration = 3
        repeat(1000) { index: Int ->
            gio.backendTypeName = if (index % 2 == 0) "SecretFileBackend" else "SecretService"
            assertEquals(index % 2 == 0, portal(if (index % 10 == 0) serviceOver() else reused), "question $index")
        }
        gio.fireAtIteration = 0
        repeat(1000) { assertEquals(false, assertIs<RawResult.Success<Boolean>>(reused.defaultCollectionLocked()).value, "collection question $it") }

        assertEquals(2000, gio.backendGetCalls, "control: every question reached libsecret")
        assertEquals(5000, ledger.acquired, "control: two registrations (push and context) per question, one more per collection reference")
        assertClean(message = "storm")
    }
}
