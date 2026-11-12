package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

/** Round 10 SUSPECT R10-S5: a header value with a lone surrogate comes back as '?' on Android and as U+FFFD on iOS (neither as it went in). */
class JournalLoneSurrogateRound10AdversarialTest {

    @Test
    fun `a header with a lone surrogate comes back from the journal codec as it went in on both platforms`() {
        for (value in listOf("a\uD800b", "\uDC00", "x\uD83D")) {
            val response = UploadResponse(200, mapOf("x-odd" to value), ByteArray(0), false)
            val decoded: DecodedOutcome = OutcomeCodec.decode(OutcomeCodec.encode(response, 5L, null))
            val ok = decoded as DecodedOutcome.Ok
            assertEquals(
                value.map { it.code },
                ok.response.headers.getValue("x-odd").map { it.code },
                "a header value with a lone surrogate must survive the journal unchanged (or at least identically on both platforms)",
            )
        }
    }
}
