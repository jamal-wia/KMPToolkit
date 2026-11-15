package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.lang.management.ManagementFactory
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Group sto-desktop, round 6 (phase A breaker round 4, m2: "a passphrase ... is only copied into an array that is wiped" was
 * not true because [toStrictUtf8] grew its buffer by copying and dropped the smaller copies unwiped). The contract pinned
 * here: the result is the frozen UTF-8 encoding, a text with no UTF-8 form fails with a message that never holds the
 * text, and exactly ONE array of the result's size is allocated, whatever the mix of one- and two-byte characters (the old
 * encoder allocated 1.1 bytes per character first and regrew on any text with more than about 10 % two-byte characters).
 */
class StrictUtf8Round6Test {

    @Test
    fun `the result is the UTF-8 of the text for seeded mixed-width samples`() {
        val seed = 20_261_129
        val random = Random(seed)
        repeat(500) { sample: Int ->
            val text: String = randomText(random, random.nextInt(0, 200))
            assertContentEquals(
                text.toByteArray(StandardCharsets.UTF_8),
                text.toStrictUtf8(),
                "seed=$seed sample=$sample: the encoding differs from UTF-8 for ${text.length} characters",
            )
            assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toLong(), text.strictUtf8Length(), "seed=$seed sample=$sample: the count disagrees")
        }
        assertContentEquals(ByteArray(0), "".toStrictUtf8(), "the empty text has an empty encoding")
        assertContentEquals("Ærøskøbing".toByteArray(StandardCharsets.UTF_8), "Ærøskøbing".toStrictUtf8(), "a text that overflowed the old 1.1-bytes-per-char buffer")
    }

    @Test
    fun `a CharBuffer is encoded like a String`() {
        val chars: CharArray = "xxÆrø€😀".toCharArray()
        assertContentEquals("xxÆrø€😀".toByteArray(StandardCharsets.UTF_8), CharBuffer.wrap(chars).toStrictUtf8())
    }

    @Test
    fun `an unpaired surrogate anywhere has no UTF-8 form and the message never holds the text`() {
        val secret = "pa55\uD800word"
        val texts: List<String> = listOf(
            "\uD800", "\uDC00", "a\uD800", "\uDC00a", "\uD800\uD800", "\uDC00\uD800", "a\uD800b", secret, "é€\uDBFF", "😀\uDC00😀",
        )
        for (text: String in texts) {
            val failure: IllegalArgumentException = assertFailsWith<IllegalArgumentException>("no UTF-8 form for ${text.length} chars") { text.toStrictUtf8() }
            assertFalse(failure.message.orEmpty().contains("pa55"), "the message holds the text: ${failure.message}")
        }
        // Control: the same text with the surrogate paired is fine, so the failure above is about the surrogate.
        assertContentEquals("pa55😀word".toByteArray(StandardCharsets.UTF_8), "pa55😀word".toStrictUtf8())
    }

    @Test
    fun `exactly one array of the result size is allocated, also for text that overflows the encoder's first estimate`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        Assume.assumeTrue("needs the JDK's allocation counter of a thread", threads != null && threads.isThreadAllocatedMemorySupported)
        checkNotNull(threads)
        threads.isThreadAllocatedMemoryEnabled = true
        val id: Long = Thread.currentThread().id
        val seed = 20_261_130
        val random = Random(seed)
        // About 40 % two-byte characters: well over the 10 % the encoder's first buffer allows for.
        val text: String = buildString {
            repeat(CHARS) { append(if (random.nextInt(10) < 4) 'é' else 'a') }
        }
        val size: Int = text.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(size > CHARS + CHARS / 5, "control: the text is well over the encoder's first estimate (1.1 bytes per char): $size bytes for $CHARS chars")
        text.toStrictUtf8() // warm-up: class loading and the encoder's own setup are not part of the measurement

        val before: Long = threads.getThreadAllocatedBytes(id)
        val bytes: ByteArray = text.toStrictUtf8()
        val allocated: Long = threads.getThreadAllocatedBytes(id) - before

        assertEquals(size, bytes.size, "seed=$seed: control: the encoding has the expected size")
        assertTrue(
            allocated <= size + SLACK_BYTES,
            "seed=$seed: encoding $CHARS chars into $size bytes allocated $allocated bytes: an intermediate buffer was made " +
                "(and dropped without being wiped); the allowance is the result array plus $SLACK_BYTES bytes",
        )
    }

    private fun randomText(random: Random, length: Int): String = buildString {
        repeat(length) {
            when (random.nextInt(6)) {
                0 -> append('é')
                1 -> append('€')
                2 -> append("😀")
                3 -> append('я')
                else -> append('a' + random.nextInt(26))
            }
        }
    }

    private companion object {
        const val CHARS: Int = 2_000_000

        /** Objects other than the array itself (a buffer view, the encoder, a result): far below the 30 % a regrown buffer costs. */
        const val SLACK_BYTES: Int = 64 * 1024
    }
}
