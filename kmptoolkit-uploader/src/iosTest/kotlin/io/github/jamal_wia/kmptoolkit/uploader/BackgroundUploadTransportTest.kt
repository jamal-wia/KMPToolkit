package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The parts of the iOS background transport that do not need `nsurlsessiond`: which session
 * identifiers it owns, its configuration rules, the completion latch the relaunch path relies on, and
 * a relaunch for a session nobody owns completing at once.
 */
class BackgroundUploadTransportTest {

    private fun transport(prefix: String = "com.example.app.outbox.upload."): BackgroundSessionUploadTransport =
        BackgroundSessionUploadTransport(prefix, BackgroundUploadConfig(sessionIdentifierPrefix = prefix), NoopLogger)

    @Test
    fun `a session identifier yields the item id only under the transport's own prefix`() {
        val transport: BackgroundSessionUploadTransport = transport()

        assertEquals("item-42", transport.itemIdOf("com.example.app.outbox.upload.item-42"))
        assertNull(transport.itemIdOf("com.example.other.upload.item-42"))
        assertNull(transport.itemIdOf("com.example.app.outbox.upload."))
    }

    @Test
    fun `the lease comes from the config`() {
        val transport = BackgroundSessionUploadTransport("p.", BackgroundUploadConfig(lease = 5.minutes), NoopLogger)

        assertEquals(5.minutes.inWholeMilliseconds, transport.leaseMillis)
    }

    @Test
    fun `the config rejects a blank prefix and a non-positive lease`() {
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(sessionIdentifierPrefix = " ") }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(lease = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(rehandFlushWindow = (-1).minutes) }
    }

    @Test
    fun `the default prefix derives from the bundle identifier`() {
        val created = createBackgroundUploadTransport() as BackgroundSessionUploadTransport

        assertTrue(created.itemIdOf("x") == null)
        // Whatever the test bundle is called, an identifier built from it with the suffix is owned.
        val bundle: String = platform.Foundation.NSBundle.mainBundle.bundleIdentifier
            ?: "io.github.jamal_wia.kmptoolkit.uploader.unbundled"
        assertEquals("id", created.itemIdOf("$bundle.uploader.upload.id"))
    }

    @Test
    fun `the completion latch runs its action once whoever fires first`() {
        var runs = 0
        val once = OnceGuard { runs++ }

        assertTrue(once.fire())
        assertFalse(once.fire())

        assertEquals(1, runs)
    }

    @Test
    fun `a relaunch for a session no transport owns completes promptly`() = runTest {
        // Well inside the 25 s safety timeout: completing here proves the ownership check, not the timeout.
        createBackgroundUploadTransport(BackgroundUploadConfig(sessionIdentifierPrefix = "owned.prefix."))
        var completed = 0

        BackgroundUploadRelaunch.handleEvents("someone.else.item-1") { completed++ }
        // Real time, not runTest's virtual clock: the relaunch path runs on Dispatchers.Default.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repeat(200) { if (completed == 0) kotlinx.coroutines.delay(10) }
        }

        assertEquals(1, completed)
    }
}
