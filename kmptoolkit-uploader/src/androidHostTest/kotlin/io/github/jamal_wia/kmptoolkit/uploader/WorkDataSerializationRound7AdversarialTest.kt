package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.Data
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Round 7 (persisted-data fidelity): WorkManager does not keep the `Data` object, it stores `Data.toByteArray()` in its
 * database and reads it back after a restart. The request must survive that byte form (the serialization writes strings
 * in the modified UTF-8 of `writeUTF`) for hostile text, with the timeouts and the engine wait at the edges of their types.
 */
class WorkDataSerializationRound7AdversarialTest {

    private val pieces: List<String> = listOf("", "a", ",", "|", "\n", "\r\n", "\u0000", "\\", "é", "é", "日本", "😀", "\uD800", "\uDC00", "﻿", "￾", "￿", "\u0001", "\u007F", "\u0080", "߿", "ࠀ", "x".repeat(30))

    private fun text(random: Random): String = buildString { repeat(random.nextInt(0, 3)) { append(pieces[random.nextInt(pieces.size)]) } }

    @Test
    fun `random requests survive the stored byte form of Data`() {
        val random = Random(77_707)
        repeat(300) { round ->
            val fields: List<UploadField> = (0 until random.nextInt(0, 4)).map {
                if (random.nextBoolean()) UploadField.Text(text(random), text(random)) else UploadField.File(text(random), text(random), text(random), text(random))
            }
            val request = UploadRequest(
                url = "https://h.example/" + text(random),
                method = listOf("POST", "PUT")[random.nextInt(2)],
                headers = (0 until random.nextInt(0, 3)).associate { "H$it" + text(random) to text(random) },
                fields = fields,
            )
            val itemId: String = text(random).ifEmpty { "id" }
            val data: Data = request.toWorkData(itemId, engineWaitMillis = Long.MAX_VALUE, connectTimeoutMillis = Int.MAX_VALUE, readTimeoutMillis = 1)
            val stored: ByteArray = if (round % 2 == 0) Data.toByteArrayInternalV1(data) else Data.toByteArrayInternalV0(data)
            val back: Data = Data.fromByteArray(stored)
            val decoded: UploadRequest? = back.toUploadRequestOrNull()
            assertNotNull(decoded, "round $round: the stored request did not decode")
            assertEquals(request, decoded, "round $round: the request changed in the stored form")
            assertEquals(itemId, back.readItemId(), "round $round: item id")
            assertEquals(Long.MAX_VALUE, back.readEngineWaitMillis(0L))
            assertEquals(Int.MAX_VALUE, back.readConnectTimeoutMillis(0))
            assertEquals(1, back.readReadTimeoutMillis(0))
        }
    }
}
