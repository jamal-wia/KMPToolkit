package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class UploadTransportConfigTest {

    @Test
    fun `the unique work name defaults to the application id, a fixed suffix and the item id`() {
        val resolved: String = UploadTransportConfig().resolveUniqueWorkName("com.example.app", "item-1")

        assertEquals("com.example.app.uploader.upload.item-1", resolved)
    }

    @Test
    fun `two items derive different unique work names`() {
        val config = UploadTransportConfig()

        assertTrue(
            config.resolveUniqueWorkName("com.example.app", "a") !=
                config.resolveUniqueWorkName("com.example.app", "b"),
        )
    }

    @Test
    fun `the work tag defaults to the application id plus a fixed suffix`() {
        assertEquals("com.example.app.uploader.upload", UploadTransportConfig().resolveWorkTag("com.example.app"))
    }

    @Test
    fun `an explicit prefix and tag win over the derived ones`() {
        val config = UploadTransportConfig(uniqueWorkNamePrefix = "uploads_", workTag = "uploads")

        assertEquals("uploads_item-1", config.resolveUniqueWorkName("com.example.app", "item-1"))
        assertEquals("uploads", config.resolveWorkTag("com.example.app"))
    }

    @Test
    fun `blank prefix or tag is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(uniqueWorkNamePrefix = "") }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workTag = "  ") }
    }

    @Test
    fun `a backoff below WorkManager's floor is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workBackoff = 9.seconds) }
        UploadTransportConfig(workBackoff = 10.seconds) // the floor itself is fine
    }

    @Test
    fun `non-positive values are rejected`() {
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(leaseMillis = 0) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(engineWait = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(connectTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(readTimeoutMillis = 0) }
    }

    @Test
    fun `the defaults are the documented ones`() {
        val config = UploadTransportConfig()

        assertEquals(15L * 60_000L, config.leaseMillis)
        assertEquals(30.seconds, config.workBackoff)
        assertEquals(5.seconds, config.engineWait)
        assertEquals(30_000, config.connectTimeoutMillis)
        assertEquals(60_000, config.readTimeoutMillis)
        assertTrue(config.requiresNetwork)
    }
}
