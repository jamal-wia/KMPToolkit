package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

class UploadResultTest {

    @Test
    fun `2xx is delivered`() {
        assertEquals(SettleResult.Delivered, defaultUploadClassification(UploadResult.Completed(200)))
        assertEquals(SettleResult.Delivered, defaultUploadClassification(UploadResult.Completed(204)))
        assertEquals(SettleResult.Delivered, defaultUploadClassification(UploadResult.Completed(299)))
    }

    @Test
    fun `4xx is dropped with the status code in the reason`() {
        val result = defaultUploadClassification(UploadResult.Completed(404))

        assertEquals(SettleResult.Drop("HTTP 404"), result)
    }

    @Test
    fun `5xx is a retryable failure`() {
        assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.Completed(500)))
        assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.Completed(503)))
    }

    @Test
    fun `a status outside 2xx-5xx is a retryable failure`() {
        assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.Completed(101)))
    }

    @Test
    fun `a transport failure is a retryable failure`() {
        assertEquals(
            SettleResult.Failed(null),
            defaultUploadClassification(UploadResult.TransportFailure("timeout")),
        )
    }
}
