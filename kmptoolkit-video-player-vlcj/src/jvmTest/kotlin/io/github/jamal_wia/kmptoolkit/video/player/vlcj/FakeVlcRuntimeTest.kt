package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixture's own contract: [FakeVlcRuntime] is shared by engines whose teardown threads release it
 * concurrently, so its `released` counter must not lose an update. A plain `released++` loses some
 * (a hundred engines once freed "99 of 100"), which made a test of the library fail for the fixture's fault.
 */
class FakeVlcRuntimeTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    @Test
    fun `releases from many threads at once are all counted`() {
        val threads: Int = 8
        val callsPerThread: Int = 5_000
        val startGun = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val workers: List<Thread> = List(threads) { index: Int ->
            Thread {
                startGun.await()
                repeat(callsPerThread) { runtime.release() }
                finished.countDown()
            }.apply {
                name = "fake-runtime-release-$index"
                isDaemon = true
            }
        }
        workers.forEach { it.start() }
        startGun.countDown()
        assertTrue(finished.await(60L, TimeUnit.SECONDS), "the release threads did not finish within 60 s")
        assertEquals(threads * callsPerThread, runtime.released, "releases counted after $threads threads of $callsPerThread calls")
        assertEquals(threads * callsPerThread, runtime.log.count { it.startsWith("runtime.release@") }, "releases logged")
    }
}
