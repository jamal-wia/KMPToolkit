package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Round 6 (differential: encode versus decode): a request encoded into WorkManager `Data` by the classic transport reads
 * back equal for random requests over hostile text (empty names, NUL, lone surrogates, 4-byte characters, separators that
 * a hand-rolled encoding might use), in wire order, with its timeouts.
 */
@RunWith(AndroidJUnit4::class)
class UploadRequestWorkDataFuzzRound6AdversarialTest {

    private val pieces: List<String> = listOf("", "a", "A", ",", ";", "|", "\n", "\r\n", "\u0000", "\\", "\"", "\u00E9", "e\u0301", "\u65E5\u672C", "\uD83D\uDE00", "\uD800", "\uDC00", " ", "=", "&", "%00", "x".repeat(40))

    private fun text(random: Random): String = buildString { repeat(random.nextInt(0, 4)) { append(pieces[random.nextInt(pieces.size)]) } }

    @Test
    fun `random requests survive the Data round trip equal`() {
        val random = Random(66_606)
        repeat(300) { round ->
            val fields: List<UploadField> = (0 until random.nextInt(0, 6)).map {
                if (random.nextBoolean()) UploadField.Text(text(random), text(random))
                else UploadField.File(text(random), text(random), text(random), text(random))
            }
            val request = UploadRequest(
                url = "https://h.example/" + text(random),
                method = listOf("POST", "PUT")[random.nextInt(2)],
                headers = (0 until random.nextInt(0, 4)).associate { "H${it}" + text(random) to text(random) },
                fields = fields,
            )
            val itemId: String = text(random).ifEmpty { "id" }
            val data = request.toWorkData(itemId, engineWaitMillis = 1234L, connectTimeoutMillis = 55, readTimeoutMillis = 66)
            val back: UploadRequest? = data.toUploadRequestOrNull()
            assertNotNull(back, "round $round: the request did not decode")
            assertEquals(request, back, "round $round: the request changed in the Data round trip")
            assertEquals(itemId, data.readItemId(), "round $round: item id")
        }
    }
}
