package io.github.jamal_wia.kmptoolkit.permission

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Host key prefixes handed out by eight real threads at once are all different, so two hosts never share a key. */
class HostKeysThreadsTest {

    @Test
    fun `eight threads taking five hundred prefixes each never get the same one twice`() {
        val taken = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val threads: List<Thread> = List(8) {
            Thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(500) { taken += PermissionHostKeys.nextPrefix() }
                done.countDown()
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS), "the threads did not finish")
        threads.forEach { it.join(2_000) }
        assertEquals(4_000, taken.size)
        assertEquals(4_000, taken.toSet().size, "two hosts were given the same key prefix")
    }
}
