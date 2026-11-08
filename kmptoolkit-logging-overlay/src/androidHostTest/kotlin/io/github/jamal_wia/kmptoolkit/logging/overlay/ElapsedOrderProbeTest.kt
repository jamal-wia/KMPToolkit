package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** Records are listed oldest first; the elapsed stamp of a later record must not be smaller than an earlier one's. */
class ElapsedOrderProbeTest {
    @Test
    fun `elapsed stamps never decrease along the list under concurrent writers`() {
        var worst = 0
        repeat(5) {
            val state = LogOverlayState(maxRecords = 100_000)
            val start = CountDownLatch(1)
            val threads = List(8) { t ->
                thread(isDaemon = true) {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(4_000) { i -> state.record(LogLevel.INFO, "t$t", "$i") }
                }
            }
            start.countDown()
            threads.forEach { it.join(30_000) }
            val list = state.records.value
            val inversions = (1 until list.size).count { list[it].elapsedMillis < list[it - 1].elapsedMillis }
            worst = maxOf(worst, inversions)
        }
        assertEquals(0, worst, "records out of elapsed order")
    }
}
