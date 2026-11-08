package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Random and extreme inputs for the engine's pure helpers; none may throw or break its stated range. */
class VlcjPureFunctionsFuzzAdversarialTest {

    @Test
    fun `displaySize never throws and returns only positive sides for any track values`() {
        val random = Random(7)
        val extremes: List<Int> = listOf(Int.MIN_VALUE, -1, 0, 1, 2, 1_000, 65_535, Int.MAX_VALUE)
        val orientations: List<String?> = listOf(null, "TOP_LEFT", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM", "weird")
        repeat(20_000) {
            fun pick(): Int = if (random.nextBoolean()) extremes.random(random) else random.nextInt(-5, 5_000)
            val size = displaySize(pick(), pick(), pick(), pick(), orientations.random(random))
            if (size != null) {
                assertTrue(size.width > 0 && size.height > 0, "non-positive size $size")
            }
        }
    }

    @Test
    fun `volumePercent stays within 0 to 100 for every finite and infinite volume`() {
        val values: List<Float> = listOf(
            -Float.MAX_VALUE, -1e30f, -1f, -0f, 0f, Float.MIN_VALUE, 0.004f, 0.005f, 0.5f, 0.994f, 0.995f, 1f, 1.5f, 1e30f,
            Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )
        for (v: Float in values) {
            val percent: Int = volumePercent(v)
            assertTrue(percent in 0..100, "volumePercent($v) = $percent")
        }
    }

    @Test
    fun `copyRv32ToArgb forces alpha opaque keeps the colour and leaves the source buffer untouched`() {
        val random = Random(11)
        repeat(300) {
            val count: Int = random.nextInt(1, 400)
            val source: ByteBuffer = ByteBuffer.allocateDirect(count * 4 + random.nextInt(0, 64)).order(ByteOrder.nativeOrder())
            val expected = IntArray(count)
            for (i in 0 until count) {
                val word: Int = random.nextInt()
                source.putInt(i * 4, word)
                expected[i] = word or (0xFF shl 24)
            }
            source.position(random.nextInt(0, source.limit() / 2))
            val positionBefore: Int = source.position()
            val target = IntArray(count + 3) { 0x12345678 }
            copyRv32ToArgb(source, target, count)
            assertEquals(expected.toList(), target.take(count))
            assertEquals(listOf(0x12345678, 0x12345678, 0x12345678), target.takeLast(3), "wrote past the pixel count")
            assertEquals(positionBefore, source.position(), "the source position moved")
        }
    }
}
