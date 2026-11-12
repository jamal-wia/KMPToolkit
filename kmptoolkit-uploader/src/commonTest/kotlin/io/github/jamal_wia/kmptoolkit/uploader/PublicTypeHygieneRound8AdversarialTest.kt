package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Round 8 (hostile consumer, public type hygiene): equals/hashCode/toString/copy of the public value types, bad
 * arguments of every public constructor, sealed and enum stability, the documented classification table.
 */
class PublicTypeHygieneRound8AdversarialTest {

    private val token = "Bearer SECRET-TOKEN-4711"

    // ---- UploadResponse -----------------------------------------------------------------------------

    @Test
    fun `UploadResponse toString prints counts only for every shape of input`() {
        val shapes: List<UploadResponse> = listOf(
            UploadResponse(200, mapOf("Authorization" to token, "X-Other" to "SECRET-HEADER"), "SECRET-BODY".encodeToByteArray(), false),
            UploadResponse(Int.MIN_VALUE, emptyMap(), ByteArray(0), true),
            UploadResponse(Int.MAX_VALUE, mapOf("" to ""), ByteArray(3), true),
        )
        for (response in shapes) {
            val text: String = response.toString()
            assertFalse("SECRET" in text || "Bearer" in text, text)
            assertTrue(text.startsWith("UploadResponse(statusCode=${response.statusCode}, headers=${response.headers.size}, body="), text)
        }
    }

    @Test
    fun `UploadResponse equals and hashCode agree and ignore header case header order and caller arrays`() {
        val body: ByteArray = byteArrayOf(1, 2, 3)
        val a = UploadResponse(200, mapOf("Content-Type" to "a", "X-B" to "b"), body, false)
        val b = UploadResponse(200, linkedMapOf("x-b" to "b", "content-type" to "a"), byteArrayOf(1, 2, 3), false)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, UploadResponse(200, a.headers, body, true))
        assertNotEquals(a, UploadResponse(201, a.headers, body, false))
        assertNotEquals(a, UploadResponse(200, a.headers, byteArrayOf(1, 2, 4), false))
        assertFalse(a.equals(null))
        assertFalse(a.equals("200"))
    }

    @Test
    fun `UploadResponse is immune to later mutation of the arrays and maps it was built from and of its own copies`() {
        val body: ByteArray = byteArrayOf(1, 2, 3)
        val source: MutableMap<String, String> = linkedMapOf("A" to "1")
        val response = UploadResponse(200, source, body, false)
        val hash: Int = response.hashCode()

        body[0] = 99
        source["B"] = "2"
        source["A"] = "changed"
        response.body()[1] = 77

        assertEquals(listOf<Byte>(1, 2, 3), response.body().toList())
        assertEquals(mapOf("a" to "1"), response.headers)
        assertEquals(hash, response.hashCode())
        assertTrue(response.body() !== response.body(), "a fresh copy each time")
    }

    @Test
    fun `UploadResponse header lookup is case-insensitive and merges repeated names with a comma`() {
        val response = UploadResponse(
            200,
            linkedMapOf("Set-Cookie" to "a=1", "set-cookie" to "b=2", "SET-COOKIE" to "c=3"),
            ByteArray(0),
            false,
        )

        assertEquals("a=1, b=2, c=3", response.header("SeT-CoOkIe"))
        assertEquals(1, response.headers.size)
        assertEquals(null, response.header("absent"))
        assertEquals(null, response.header(""))
    }

    @Test
    fun `UploadResponse accepts any status and a header set of a hundred thousand names`() {
        val many: Map<String, String> = (0 until 100_000).associate { "h$it" to "v" }
        val response = UploadResponse(-1, many, ByteArray(0), false)

        assertEquals(100_000, response.headers.size)
        assertEquals(-1, response.statusCode)
        assertEquals("v", response.header("H99999"))
    }

    // ---- data classes: equals / hashCode / copy / toString ---------------------------------------------

    @Test
    fun `UploaderItem copy keeps every field and equals follows the fields`() {
        val item = UploaderItem(
            id = "i", type = "t", payload = "p", schemaVersion = 3, uniqueKey = "k", orderingKey = "o", tag = "g",
            state = UploaderItemState.IN_FLIGHT, attempts = 4, nextRunAtEpochMillis = 5, createdAtEpochMillis = 6,
            lastError = "e", leaseUntilEpochMillis = 7, claim = 8,
        )

        assertEquals(item, item.copy())
        assertEquals(item.hashCode(), item.copy().hashCode())
        assertNotEquals(item, item.copy(claim = 9))
        assertNotEquals(item, item.copy(state = UploaderItemState.PENDING))
        assertEquals(0L, item.copy(leaseUntilEpochMillis = 0L).leaseUntilEpochMillis)
    }

    @Test
    fun `AttemptContext and the request value types behave as data`() {
        val a = AttemptContext("id", 1, "k", "o", 2L)
        assertEquals(a, a.copy())
        assertFalse(a.wasDetached)
        assertEquals(a.hashCode(), AttemptContext("id", 1, "k", "o", 2L, false).hashCode())

        val request = UploadRequest("https://example.com", fields = listOf(UploadField.Text("n", "v")))
        assertEquals("POST", request.method)
        assertEquals(request, request.copy())
        assertEquals(request.hashCode(), request.copy().hashCode())
        assertNotEquals(request, request.copy(method = "PUT"))
        assertEquals(UploadField.File("a", "b", "c", "d"), UploadField.File("a", "b", "c", "d"))
        assertNotEquals<UploadField>(UploadField.Text("a", "b"), UploadField.File("a", "b", "c", "d"))
    }

    @Test
    fun `result types have value equality and readable names`() {
        assertEquals(AttemptResult.Retry(null), AttemptResult.Retry())
        assertEquals(SettleResult.Failed(null), SettleResult.Failed())
        assertEquals("Success", AttemptResult.Success.toString())
        assertEquals("Delivered", SettleResult.Delivered.toString())
        val cause = IllegalStateException("x")
        assertEquals(AttemptResult.Retry(cause), AttemptResult.Retry(cause))
        assertNotEquals<AttemptResult>(AttemptResult.Drop("a"), AttemptResult.Park("a"))
        assertNotEquals<SettleResult>(SettleResult.Drop("a"), SettleResult.Park("a"))
        assertEquals(UploadResult.Completed(200), UploadResult.Completed(200))
        assertNotEquals<UploadResult>(UploadResult.Completed(0), UploadResult.TransportFailure(null))
        assertEquals(UploadResult.TransportFailure(null), UploadResult.TransportFailure(null))
        assertEquals(UploadAttempt.NothingOwed, UploadAttempt.NothingOwed)
        assertNotEquals<UploadAttempt>(UploadAttempt.NothingOwed, UploadAttempt.EngineUnavailable)
    }

    // ---- exhaustiveness and enum stability (a new variant must break this build on purpose) ------------

    private fun label(r: AttemptResult): String = when (r) {
        AttemptResult.Success -> "success"
        is AttemptResult.Retry -> "retry"
        is AttemptResult.Drop -> "drop"
        is AttemptResult.Park -> "park"
        is AttemptResult.Detached -> "detached"
    }

    private fun label(r: SettleResult): String = when (r) {
        SettleResult.Delivered -> "delivered"
        is SettleResult.Failed -> "failed"
        is SettleResult.Drop -> "drop"
        is SettleResult.Park -> "park"
    }

    private fun label(r: UploadResult): String = when (r) {
        is UploadResult.Completed -> "completed"
        is UploadResult.TransportFailure -> "failure"
    }

    private fun label(r: UploadPreparation): String = when (r) {
        is UploadPreparation.Proceed -> "proceed"
        is UploadPreparation.Drop -> "drop"
        is UploadPreparation.Park -> "park"
    }

    private fun label(r: UploadAttempt): String = when (r) {
        is UploadAttempt.Ready -> "ready"
        UploadAttempt.NothingOwed -> "nothing"
        UploadAttempt.EngineUnavailable -> "unavailable"
    }

    private fun label(r: GiveUpPolicy): String = when (r) {
        GiveUpPolicy.Never -> "never"
        is GiveUpPolicy.ParkAfterAttempts -> "park"
        is GiveUpPolicy.DropAfterAttempts -> "drop"
    }

    private fun label(r: UploadField): String = when (r) {
        is UploadField.Text -> "text"
        is UploadField.File -> "file"
    }

    @Test
    fun `every sealed result type is exhaustive and every enum keeps its entries in order`() {
        assertEquals("success", label(AttemptResult.Success))
        assertEquals("detached", label(AttemptResult.Detached(1)))
        assertEquals("delivered", label(SettleResult.Delivered))
        assertEquals("failure", label(UploadResult.TransportFailure(null)))
        assertEquals("drop", label(UploadPreparation.Drop("x")))
        assertEquals("unavailable", label(UploadAttempt.EngineUnavailable))
        assertEquals("never", label(GiveUpPolicy.Never))
        assertEquals("file", label(UploadField.File("a", "b", "c", "d")))

        assertEquals(listOf("PENDING", "IN_FLIGHT", "PARKED"), UploaderItemState.entries.map { it.name })
        assertEquals(listOf("KEEP", "REPLACE"), ConflictPolicy.entries.map { it.name })
        assertEquals(listOf("RETRY", "PARK", "DROP"), FailureDisposition.entries.map { it.name })
    }

    // ---- bad arguments of every public constructor ------------------------------------------------------

    @Test
    fun `Detached and the give-up policies reject non-positive numbers and accept the smallest valid one`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { AttemptResult.Detached(bad) }
        assertEquals(1L, AttemptResult.Detached(1).leaseMillis)
        assertEquals(Long.MAX_VALUE, AttemptResult.Detached(Long.MAX_VALUE).leaseMillis)
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { GiveUpPolicy.ParkAfterAttempts(bad) }
            assertFailsWith<IllegalArgumentException> { GiveUpPolicy.DropAfterAttempts(bad) }
        }
        GiveUpPolicy.ParkAfterAttempts(Int.MAX_VALUE)
        GiveUpPolicy.DropAfterAttempts(1)
    }

    @Test
    fun `the exponential policy rejects every bad argument with an IllegalArgumentException`() {
        for (base in listOf(0L, -1L, Long.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { ExponentialBackoffRetryPolicy(baseDelayMillis = base) }
        assertFailsWith<IllegalArgumentException> { ExponentialBackoffRetryPolicy(baseDelayMillis = 10, maxDelayMillis = 9) }
        assertFailsWith<IllegalArgumentException> { ExponentialBackoffRetryPolicy(maxDelayMillis = Long.MIN_VALUE) }
        for (j in listOf(-0.0001, 1.0001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ExponentialBackoffRetryPolicy(jitterRatio = j) }
        }
        ExponentialBackoffRetryPolicy(baseDelayMillis = 1, maxDelayMillis = 1, jitterRatio = 0.0)
        ExponentialBackoffRetryPolicy(baseDelayMillis = Long.MAX_VALUE, maxDelayMillis = Long.MAX_VALUE, jitterRatio = 1.0)
    }

    @Test
    fun `UploaderConfig rejects non-positive durations and a factor below one and accepts the extremes`() {
        for (d in listOf(Duration.ZERO, (-1).milliseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException> { UploaderConfig(heartbeatInterval = d) }
            assertFailsWith<IllegalArgumentException> { UploaderConfig(minAlarmDelay = d) }
            assertFailsWith<IllegalArgumentException> { UploaderConfig(drainPollInterval = d) }
        }
        for (f in listOf(0, -1, Int.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { UploaderConfig(clockAnomalyFactor = f) }
        UploaderConfig(clockAnomalyFactor = 1)
        UploaderConfig(clockAnomalyFactor = Int.MAX_VALUE)
        UploaderConfig(heartbeatInterval = 1.milliseconds, minAlarmDelay = 1.milliseconds, drainPollInterval = 1.milliseconds)
        assertFailsWith<IllegalArgumentException> { UploaderConfig().copy(heartbeatInterval = Duration.ZERO) }
        assertEquals(UploaderConfig(), UploaderConfig().copy())
        assertEquals(30.seconds, UploaderConfig.DEFAULT_HEARTBEAT_INTERVAL)
    }

    // ---- the documented classification table ------------------------------------------------------------

    @Test
    fun `defaultUploadClassification follows the documented table at every boundary`() {
        for (status in listOf(200, 201, 204, 250, 299)) {
            assertEquals(SettleResult.Delivered, defaultUploadClassification(UploadResult.Completed(status)), "$status")
        }
        for (status in listOf(400, 401, 404, 408, 422, 429, 499)) {
            assertEquals(SettleResult.Drop("HTTP $status"), defaultUploadClassification(UploadResult.Completed(status)), "$status")
        }
        for (status in listOf(Int.MIN_VALUE, -1, 0, 1, 100, 199, 300, 301, 304, 399, 500, 503, 599, 600, Int.MAX_VALUE)) {
            assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.Completed(status)), "$status")
        }
        assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.TransportFailure("x")))
        assertEquals(SettleResult.Failed(null), defaultUploadClassification(UploadResult.TransportFailure(null)))
    }

    @Test
    fun `the system clock reads a plausible epoch and does not go backwards over a burst`() {
        var last: Long = UploaderClock.System.nowEpochMillis()
        assertTrue(last > 1_600_000_000_000L)
        repeat(10_000) {
            val now: Long = UploaderClock.System.nowEpochMillis()
            assertTrue(now >= last - 5, "a regression of ${last - now} ms inside one burst")
            last = maxOf(last, now)
        }
        assertNotNull(UploaderClock.System)
    }
}
