package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathAccessGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R-15: the in-process gate of the Windows ops, tested directly on every host. Every wait is bounded. */
class PathAccessGateTest {
    private val gate: PathAccessGate = PathAccessGate()

    private fun waitUntilBlocked(t: Thread) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.TIMED_WAITING && t.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < deadline) { "the thread never blocked: ${t.state}" }
            Thread.yield()
        }
    }

    @Test
    fun `many shared permits on one path are held together`() {
        val arrived: CountDownLatch = CountDownLatch(8)
        val go: CountDownLatch = CountDownLatch(1)
        val threads: List<Thread> = List(8) {
            thread {
                val permit: PathAccessGate.Permit = gate.shared("/d/x")
                arrived.countDown()
                go.await(20, TimeUnit.SECONDS)
                permit.release()
            }
        }
        assertTrue(arrived.await(20, TimeUnit.SECONDS), "all eight readers must hold the path at once")
        assertEquals(1, gate.activeKeyCount)
        go.countDown()
        threads.forEach { it.join(20_000) }
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `an exclusive permit waits for every shared holder and a later shared request waits for it`() {
        val order: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val firstReader: PathAccessGate.Permit = gate.shared("/d/x")
        val writer: Thread = thread {
            val permit: PathAccessGate.Permit = gate.exclusive("/d/x")
            order += "exclusive"
            permit.release()
        }
        waitUntilBlocked(writer)
        assertTrue(order.isEmpty(), "the exclusive permit must wait for the reader")
        // Fair: this later reader queues behind the waiting writer instead of overtaking it.
        val lateReader: Thread = thread {
            val permit: PathAccessGate.Permit = gate.shared("/d/x")
            order += "shared"
            permit.release()
        }
        waitUntilBlocked(lateReader)
        assertTrue(order.isEmpty(), "the late reader must not overtake the waiting writer")
        firstReader.release()
        writer.join(20_000)
        lateReader.join(20_000)
        assertEquals(listOf("exclusive", "shared"), order)
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `exclusive permits on a b and b a from sixteen threads never deadlock`() {
        val done: AtomicInteger = AtomicInteger(0)
        val threads: List<Thread> = List(16) { index ->
            thread {
                repeat(500) {
                    val permit: PathAccessGate.Permit =
                        if (index % 2 == 0) gate.exclusive("/d/a", "/d/b") else gate.exclusive("/d/b", "/d/a")
                    permit.release()
                }
                done.incrementAndGet()
            }
        }
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        for (t: Thread in threads) t.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
        assertEquals(16, done.get(), "a deadlock: ${threads.count { it.isAlive }} threads are stuck")
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `keys fold by PathKey so spellings of one path share a permit`() {
        val held: PathAccessGate.Permit = gate.shared("C:\\D\\X")
        val result: AtomicInteger = AtomicInteger(0)
        val writer: Thread = thread {
            val permit: PathAccessGate.Permit = gate.exclusive("c:\\d\\x")
            result.set(1)
            permit.release()
        }
        waitUntilBlocked(writer)
        assertEquals(0, result.get(), "another spelling of the held path must wait")
        held.release()
        writer.join(20_000)
        assertEquals(1, result.get())
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `release is idempotent and the registry empties`() {
        val shared: PathAccessGate.Permit = gate.shared("/d/x")
        shared.release()
        shared.release()
        val exclusive: PathAccessGate.Permit = gate.exclusive("/d/x", "/d/x", "/d/y")
        assertEquals(2, gate.activeKeyCount)
        exclusive.release()
        exclusive.release()
        assertEquals(0, gate.activeKeyCount)
        // Usable again after the double release.
        gate.exclusive("/d/x").release()
        assertFalse(gate.activeKeyCount != 0)
    }

    @Test
    fun `a shared permit released by another thread is really released`() {
        val permit: PathAccessGate.Permit = gate.shared("/d/x")
        val releaser: Thread = thread { permit.release() }
        releaser.join(20_000)
        val acquired: CountDownLatch = CountDownLatch(1)
        val writer: Thread = thread {
            gate.exclusive("/d/x").release()
            acquired.countDown()
        }
        assertTrue(acquired.await(20, TimeUnit.SECONDS), "a replace must not be blocked by a permit closed on another thread")
        writer.join(20_000)
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `an exclusive permit released by another thread is really released and two releases at once free it once`() {
        val permit: PathAccessGate.Permit = gate.exclusive("/d/x", "/d/y")
        val start: CountDownLatch = CountDownLatch(1)
        val releasers: List<Thread> = List(2) { thread { start.await(20, TimeUnit.SECONDS); permit.release() } }
        start.countDown()
        releasers.forEach { it.join(20_000) }
        assertEquals(0, gate.activeKeyCount)
        // Released once only: a second unlock would let two exclusive holders in.
        val first: PathAccessGate.Permit = gate.exclusive("/d/x")
        val second: AtomicInteger = AtomicInteger(0)
        val other: Thread = thread { gate.exclusive("/d/x").release(); second.set(1) }
        waitUntilBlocked(other)
        assertEquals(0, second.get(), "the path is exclusively held, nobody else may enter")
        first.release()
        other.join(20_000)
        assertEquals(1, second.get())
        assertEquals(0, gate.activeKeyCount)
    }

    @Test
    fun `an interrupted wait for an exclusive permit throws and leaves nothing held`() {
        val reader: PathAccessGate.Permit = gate.shared("/d/y")
        val thrown: java.util.concurrent.atomic.AtomicReference<Throwable?> = java.util.concurrent.atomic.AtomicReference(null)
        val writer: Thread = thread {
            try {
                gate.exclusive("/d/x", "/d/y").release()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        waitUntilBlocked(writer)
        writer.interrupt()
        writer.join(20_000)
        assertTrue(thrown.get() is InterruptedException, "the interrupt must end the wait: ${thrown.get()}")
        // /d/x is taken first (ascending order) and /d/y awaited: the first must have been given back.
        assertEquals(1, gate.activeKeyCount, "only the reader's path is registered")
        gate.exclusive("/d/x").release()
        reader.release()
        assertEquals(0, gate.activeKeyCount)
        gate.exclusive("/d/y").release()
    }

    @Test
    fun `an interrupted wait for a shared permit behind an exclusive holder throws and leaves nothing held`() {
        val writer: PathAccessGate.Permit = gate.exclusive("/d/x")
        val thrown: java.util.concurrent.atomic.AtomicReference<Throwable?> = java.util.concurrent.atomic.AtomicReference(null)
        val reader: Thread = thread {
            try {
                gate.shared("/d/x").release()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        waitUntilBlocked(reader)
        reader.interrupt()
        reader.join(20_000)
        assertTrue(thrown.get() is InterruptedException)
        writer.release()
        assertEquals(0, gate.activeKeyCount)
        gate.shared("/d/x").release()
    }
}
