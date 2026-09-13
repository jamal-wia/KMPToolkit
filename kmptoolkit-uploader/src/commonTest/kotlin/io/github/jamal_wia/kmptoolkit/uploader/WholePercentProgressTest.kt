package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

class WholePercentProgressTest {

    private val reported = mutableListOf<Float>()

    @Test
    fun `each whole percent is reported once however the bytes are chunked`() {
        val progress = WholePercentProgress(totalBytes = 1_000) { reported += it }

        repeat(1_000) { progress.add(1) }

        assertEquals(101, reported.size, "0% through 100%, each once")
        assertEquals(0f, reported.first())
        assertEquals(1f, reported.last())
    }

    @Test
    fun `a cumulative total that does not change the percent reports nothing new`() {
        val progress = WholePercentProgress(totalBytes = 10_000) { reported += it }

        progress.set(5_000)
        progress.set(5_050)
        progress.set(5_100)

        assertEquals(listOf(0.5f, 0.51f), reported)
    }

    @Test
    fun `an unknown total reports nothing and an overshoot is clamped`() {
        WholePercentProgress(totalBytes = 0) { reported += it }.add(10)
        assertEquals(emptyList(), reported)

        WholePercentProgress(totalBytes = 10) { reported += it }.add(20)
        assertEquals(listOf(1f), reported)
    }
}
