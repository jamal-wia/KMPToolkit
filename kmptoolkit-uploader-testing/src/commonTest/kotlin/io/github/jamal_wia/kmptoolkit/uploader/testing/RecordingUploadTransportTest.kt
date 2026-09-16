package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingUploadTransportTest {

    private val request = UploadRequest(url = "https://example.com", fields = emptyList())

    @Test
    fun `both launch overloads are recorded with the re-hand flag`() {
        val transport = RecordingUploadTransport()

        transport.launch("a", request)
        transport.launch("b", isRehandOff = true, request = request)

        assertEquals(
            listOf(
                RecordingUploadTransport.Launch("a", false, request),
                RecordingUploadTransport.Launch("b", true, request),
            ),
            transport.launches,
        )
    }

    @Test
    fun `a scripted failure is thrown and nothing is recorded`() {
        val transport = RecordingUploadTransport()
        transport.failLaunchWith = IllegalStateException("unavailable")

        assertFailsWith<IllegalStateException> { transport.launch("a", request) }
        assertEquals(emptyList(), transport.launches)
    }

    @Test
    fun `cancelAll is counted and the lease is configurable`() {
        val transport = RecordingUploadTransport(leaseMillis = 5)

        transport.cancelAll()

        assertEquals(1, transport.cancelAllCount)
        assertEquals(5L, transport.leaseMillis)
    }
}
