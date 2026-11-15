package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.CharBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group sto-desktop, phase A breaker round 5 (decisions.md D-23), part A5: the round-6 `toStrictUtf8` ("counts first, encodes
 * into ONE array of exactly that size, wipes it before it throws") attacked as its KDoc states it:
 *
 * - every Unicode scalar value alone, and the width boundaries (U+007F/U+0080, U+07FF/U+0800, U+FFFF/U+10000, U+10FFFF) at
 *   every position of a mixed text: bytes equal the JDK's UTF-8 and `strictUtf8Length` equals their count;
 * - an unpaired surrogate at every position (lone high, lone low, a reversed pair, a slice that cuts a pair at either end):
 *   `IllegalArgumentException` whose message never holds the text;
 * - a `CharBuffer` whose position and limit do not cover its array: only the remaining characters are encoded;
 * - a text too long for one array is refused by counting, before anything is allocated;
 * - "a text that has no UTF-8 form wipes the array before it is dropped", and the same for the other two ways the encoding can
 *   stop (a source that throws mid-way, a source whose length changes between the count and the encoding): proven on a heap
 *   dump that includes unreachable objects, with a control that the dump scan finds an unwiped encoding that is still held.
 *
 * The dump search uses encodings of Latin-1 supplement text made at run time from `Random(seed)`: a `String` stores such text as
 * one byte per character and a `char[]` as UTF-16, so the UTF-8 byte sequence exists on the heap only where an encoder wrote it.
 */
class StrictUtf8A5BreakerR5Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-utf8-breaker5-a5").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `every scalar value alone encodes to the JDK's UTF-8 and is counted exactly`() {
        var codePoint = 0
        while (codePoint <= Character.MAX_CODE_POINT) {
            if (codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
                val text = String(Character.toChars(codePoint))
                val expected: ByteArray = text.toByteArray(Charsets.UTF_8)
                val actual: ByteArray = text.toStrictUtf8()
                if (!expected.contentEquals(actual)) assertContentEquals(expected, actual, "U+%04X".format(codePoint))
                if (text.strictUtf8Length() != expected.size.toLong()) assertEquals(expected.size.toLong(), text.strictUtf8Length(), "U+%04X".format(codePoint))
            }
            codePoint++
        }
    }

    @Test
    fun `width boundaries at every position of a mixed text are encoded and counted exactly`() {
        val boundaries: List<Int> = listOf(0x00, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFD, 0xFFFF, 0x10000, 0x10FFFF)
        val base: List<String> = listOf("a", "é", "中", "😀", "z", "߿")
        for (boundary: Int in boundaries) {
            for (position: Int in 0..base.size) {
                val pieces: MutableList<String> = base.toMutableList()
                pieces.add(position, String(Character.toChars(boundary)))
                val text: String = pieces.joinToString("")
                val expected: ByteArray = text.toByteArray(Charsets.UTF_8)
                assertContentEquals(expected, text.toStrictUtf8(), "U+%04X at %d".format(boundary, position))
                assertEquals(expected.size.toLong(), text.strictUtf8Length(), "U+%04X at %d".format(boundary, position))
                assertContentEquals(expected, StringBuilder(text).toStrictUtf8(), "StringBuilder, U+%04X at %d".format(boundary, position))
            }
        }
    }

    @Test
    fun `an unpaired surrogate at every position has no UTF-8 form and the message never holds the text`() {
        val base = "Sécr中t😀pw"
        val intruders: Map<String, String> = mapOf(
            "lone high" to "\uD800",
            "lone low" to "\uDC00",
            "reversed pair" to "\uDC00\uD800",
            "two highs" to "\uDBFF\uDBFF",
        )
        for ((name: String, intruder: String) in intruders) {
            for (position: Int in 0..base.length) {
                // Never between the two halves of the emoji already in the text: an intruder there can pair with them (tested on slices below).
                if (position in 1 until base.length && base[position - 1].isHighSurrogate() && base[position].isLowSurrogate()) continue
                val text: String = base.substring(0, position) + intruder + base.substring(position)
                val error: IllegalArgumentException = assertFailsWith<IllegalArgumentException>("$name at $position") { text.toStrictUtf8() }
                assertFalse(error.message.orEmpty().contains("cr"), "$name at $position: the message holds the text: ${error.message}")
                assertTrue(text.strictUtf8Length() >= text.length.toLong(), "$name at $position: the count is below one byte per char")
            }
        }
        // A slice that cuts a surrogate pair: the half inside the slice is unpaired there, whatever the array holds around it.
        val array: CharArray = "ab😀cd".toCharArray()
        assertFailsWith<IllegalArgumentException>("slice ending on a high surrogate") { CharBuffer.wrap(array, 0, 3).toStrictUtf8() }
        assertFailsWith<IllegalArgumentException>("slice starting on a low surrogate") { CharBuffer.wrap(array, 3, 3).toStrictUtf8() }
        assertEquals(5L, CharBuffer.wrap(array, 0, 3).strictUtf8Length(), "a cut high surrogate is counted as 3 bytes, the pair is not looked for past the slice")
    }

    @Test
    fun `a CharBuffer is encoded from its position to its limit only and is left where it was`() {
        val array: CharArray = "xxét😀中yy".toCharArray()
        val buffer: CharBuffer = CharBuffer.wrap(array)
        buffer.position(2)
        buffer.limit(array.size - 2)
        val expected: ByteArray = String(array, 2, array.size - 4).toByteArray(Charsets.UTF_8)

        assertContentEquals(expected, buffer.toStrictUtf8())
        assertEquals(expected.size.toLong(), buffer.strictUtf8Length())
        assertEquals(2, buffer.position(), "the caller's buffer was moved")
        assertEquals(array.size - 2, buffer.limit(), "the caller's buffer limit was changed")
    }

    @Test
    fun `a text too long for one array is refused by counting before anything is allocated`() {
        // Int.MAX_VALUE / 2 + 1 two-byte characters: just over Int.MAX_VALUE bytes. Nothing of that size can be allocated, so an
        // implementation that allocates first throws OutOfMemoryError instead of the documented IllegalArgumentException.
        val length: Int = Int.MAX_VALUE / 2 + 1
        val huge = object : CharSequence {
            override val length: Int = length
            override fun get(index: Int): Char = '\u00E9'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw UnsupportedOperationException()
        }

        assertEquals(2L * length, huge.strictUtf8Length(), "the count does not overflow")
        val error: IllegalArgumentException = assertFailsWith<IllegalArgumentException> { huge.toStrictUtf8() }
        assertFalse(error.message.orEmpty().contains("\u00E9"), "the message holds the text")
    }

    @Test
    fun `every way the encoding can stop wipes the array, proven on a heap dump with a held control`() {
        val random = Random(SEED)
        fun marker(): String = String(CharArray(MARKER_CHARS) { (0xC0 + random.nextInt(0x40)).toChar() })
        val surrogate: String = marker()
        val throwing: String = marker()
        val growing: String = marker()
        val held: String = marker()

        // 1. No UTF-8 form: the marker is written, then the unpaired surrogate stops the encoder.
        assertFailsWith<IllegalArgumentException>("seed=$SEED") { (surrogate + "\uD800").toStrictUtf8() }
        // 2. The source throws in the middle of the encoding (its last character, on the encoding pass, after the count).
        val reads = AtomicInteger()
        val throwingSource = object : CharSequence {
            override val length: Int = throwing.length + 1
            override fun get(index: Int): Char {
                if (index < throwing.length) return throwing[index]
                if (reads.incrementAndGet() > 1) throw SourceFailure()
                return 'a'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw UnsupportedOperationException()
        }
        val thrown: Throwable = assertFailsWith<Throwable>("seed=$SEED") { throwingSource.toStrictUtf8() }
        // The JDK wraps a RuntimeException out of the source in a CoderMalfunctionError; either way it must be the source's failure.
        assertTrue(generateSequence(thrown) { it.cause }.any { it is SourceFailure }, "seed=$SEED: another failure: $thrown")
        // 3. The source grows between the count (one byte) and the encoding (two bytes): the array is full before the end.
        val growingReads = AtomicInteger()
        val growingSource = object : CharSequence {
            override val length: Int = growing.length + 1
            override fun get(index: Int): Char {
                if (index < growing.length) return growing[index]
                return if (growingReads.incrementAndGet() > 1) 'é' else 'a'
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw UnsupportedOperationException()
        }
        assertFailsWith<IllegalStateException>("seed=$SEED") { growingSource.toStrictUtf8() }
        // Control: an encoding that is still held, never wiped.
        val control: ByteArray = held.toStrictUtf8()

        val dump = File(directory, "heap.hprof")
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).dumpHeap(dump.absolutePath, false)
        val found: Map<String, Boolean> = searchFile(
            dump,
            mapOf(
                "held control" to held.toByteArray(Charsets.UTF_8),
                "unpaired surrogate" to surrogate.toByteArray(Charsets.UTF_8),
                "source that throws" to throwing.toByteArray(Charsets.UTF_8),
                "source that grows" to growing.toByteArray(Charsets.UTF_8),
            ),
        )

        assertTrue(found.getValue("held control"), "seed=$SEED control: the dump scan must find an unwiped encoding that is still held")
        assertEquals(MARKER_CHARS * 2, control.size, "seed=$SEED control: the held encoding is two bytes per char")
        for (path: String in listOf("unpaired surrogate", "source that throws", "source that grows")) {
            assertFalse(
                found.getValue(path),
                "seed=$SEED: the KDoc says the array is wiped before it is dropped, but the encoding of a failed call ($path) is on the heap",
            )
        }
    }

    private class SourceFailure : RuntimeException("the source failed (breaker A5)")

    /** Which of [patterns] occur in [file], read in chunks that overlap by the longest pattern. */
    private fun searchFile(file: File, patterns: Map<String, ByteArray>): Map<String, Boolean> {
        val found: MutableMap<String, Boolean> = patterns.keys.associateWith { false }.toMutableMap()
        val overlap: Int = patterns.values.maxOf { it.size } - 1
        val chunk = ByteArray(CHUNK_BYTES + overlap)
        file.inputStream().buffered(CHUNK_BYTES).use { input ->
            var carried = 0
            while (true) {
                val read: Int = input.readNBytes(chunk, carried, CHUNK_BYTES)
                if (read <= 0) break
                val filled: Int = carried + read
                for ((name: String, pattern: ByteArray) in patterns) {
                    if (found.getValue(name)) continue
                    if (indexOf(chunk, filled, pattern)) found[name] = true
                }
                carried = minOf(overlap, filled)
                System.arraycopy(chunk, filled - carried, chunk, 0, carried)
            }
        }
        return found
    }

    private fun indexOf(haystack: ByteArray, size: Int, needle: ByteArray): Boolean {
        val first: Byte = needle[0]
        var start = 0
        while (start <= size - needle.size) {
            if (haystack[start] == first) {
                var matched = 1
                while (matched < needle.size && haystack[start + matched] == needle[matched]) matched++
                if (matched == needle.size) return true
            }
            start++
        }
        return false
    }

    private companion object {
        const val SEED: Int = 20_261_130
        const val MARKER_CHARS: Int = 24
        const val CHUNK_BYTES: Int = 8 * 1024 * 1024
    }
}
