package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Many threads asking for the default activity access of one Application at the same moment, on Applications that have never
 * had one: they must all get the one tracker, and the Application must carry exactly one lifecycle callback afterwards (a
 * second tracker created by a racing thread would register a callback nothing can ever remove).
 */
@RunWith(AndroidJUnit4::class)
class SharedTrackerContentionRound12Test {

    private fun callbacks(application: Application): Int {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        val list = field.get(application) as Collection<*>
        return synchronized(list) { list.size }
    }

    @Test
    fun `threads racing for the default access of a fresh application share one tracker and register one callback`() {
        repeat(ROUNDS) { round: Int ->
            val application = Application()
            val start = CountDownLatch(1)
            val done = CountDownLatch(THREADS)
            val got: ConcurrentLinkedQueue<ActivityAccess> = ConcurrentLinkedQueue()
            val failures: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue()
            val threads: List<Thread> = List(THREADS) { index: Int ->
                Thread {
                    try {
                        start.await()
                        got += SharedActivityAccess.forApplication(application)
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        done.countDown()
                    }
                }.apply { name = "shared-tracker-$round-$index"; isDaemon = true; start() }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "round $round: threads are stuck")
            threads.forEach { it.join(5_000) }
            assertEquals(emptyList(), failures.toList(), "round $round: a thread failed")
            assertEquals(THREADS, got.size, "round $round: threads that got an access")
            assertEquals(1, got.toSet().size, "round $round: racing threads got different trackers")
            assertEquals(1, callbacks(application), "round $round: lifecycle callbacks on the application")
        }
    }

    private companion object {
        const val ROUNDS: Int = 150
        const val THREADS: Int = 32
    }
}
