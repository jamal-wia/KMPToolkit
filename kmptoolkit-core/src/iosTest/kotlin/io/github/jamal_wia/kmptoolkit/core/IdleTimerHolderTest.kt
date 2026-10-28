package io.github.jamal_wia.kmptoolkit.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bookkeeping behind every keep-screen-on in the suite. `UIApplication.idleTimerDisabled` is one
 * app-wide flag, shared by any number of holders and by the app itself.
 *
 * A fake flag and a fake main queue stand in for UIKit. "Queued from another thread, then overtaken
 * by a main-thread call" is then a sequence of statements, not timing.
 */
@OptIn(ToolkitInternalApi::class)
class IdleTimerHolderTest {

    private class Fixture(initial: Boolean = false) {
        var value: Boolean = initial
        val writes: MutableList<Boolean> = mutableListOf()
        var onMainThread: Boolean = true
        val queue: ArrayDeque<() -> Unit> = ArrayDeque()

        val holder: IdleTimerHolder = newIdleTimerHolder(
            readFlag = { value },
            writeFlag = { disabled -> value = disabled; writes += disabled },
            isMainThread = { onMainThread },
            postToMain = { block -> queue.addLast(block) },
        )

        fun <T> offMainThread(block: () -> T): T {
            onMainThread = false
            try {
                return block()
            } finally {
                onMainThread = true
            }
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    @Test
    fun `the first hold disables the timer and the last release restores it`() {
        val flag = Fixture()

        val hold: IdleTimerHold = flag.holder.acquire()
        assertEquals(true, flag.value)

        hold.release()
        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes)
    }

    @Test
    fun `two holders keep the timer disabled until both release`() {
        val flag = Fixture()

        val first: IdleTimerHold = flag.holder.acquire()
        val second: IdleTimerHold = flag.holder.acquire()
        first.release()
        assertEquals(true, flag.value, "the second holder still wants the screen on")

        second.release()
        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes, "no write between the first hold and the last release")
    }

    @Test
    fun `the order the holders release in does not matter`() {
        val flag = Fixture()

        val first: IdleTimerHold = flag.holder.acquire()
        val second: IdleTimerHold = flag.holder.acquire()
        second.release()
        assertEquals(true, flag.value, "the first holder still wants the screen on")

        first.release()
        assertEquals(false, flag.value)
    }

    @Test
    fun `an app that disabled the timer itself keeps it disabled`() {
        val flag = Fixture(initial = true)

        flag.holder.acquire().release()

        assertEquals(true, flag.value)
    }

    @Test
    fun `the value saved is the one at the first hold of each round`() {
        val flag = Fixture()
        flag.holder.acquire().release()

        // The app changes its mind between two rounds.
        flag.value = true
        flag.holder.acquire().release()

        assertEquals(true, flag.value)
    }

    @Test
    fun `releasing one hold twice cannot release somebody else's`() {
        val flag = Fixture()
        val first: IdleTimerHold = flag.holder.acquire()
        val second: IdleTimerHold = flag.holder.acquire()

        first.release()
        first.release()

        assertEquals(true, flag.value, "the second release of the first hold must not count against the second")
        second.release()
        assertEquals(false, flag.value)
        assertEquals(listOf(true, false), flag.writes)
    }

    @Test
    fun `a double release does not underflow the count`() {
        val flag = Fixture()
        val hold: IdleTimerHold = flag.holder.acquire()
        hold.release()
        hold.release()

        // Had the count gone to -1, this hold would bring it back to 0 and leave the timer enabled.
        flag.holder.acquire()

        assertEquals(true, flag.value)
    }

    @Test
    fun `a hold taken off the main thread is written once the main queue runs`() {
        val flag = Fixture()

        flag.offMainThread { flag.holder.acquire() }
        assertEquals(false, flag.value, "UIKit is not touched off the main thread")
        flag.drain()

        assertEquals(true, flag.value)
    }

    @Test
    fun `a release on the main thread is not undone by an earlier hold queued from another thread`() {
        val flag = Fixture()
        val hold: IdleTimerHold = flag.offMainThread { flag.holder.acquire() }

        hold.release()
        flag.drain()

        assertEquals(false, flag.value, "the last call was a release; the queued hold must not land after it")
    }

    @Test
    fun `a hold on the main thread is not undone by an earlier release queued from another thread`() {
        val flag = Fixture()
        val first: IdleTimerHold = flag.holder.acquire()

        flag.offMainThread { first.release() }
        flag.holder.acquire()
        flag.drain()

        assertEquals(true, flag.value, "the last call was a hold; the queued release must not land after it")
    }
}
