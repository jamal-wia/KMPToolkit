package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A native block that remembers whether its bytes were all zero at the moment it was freed. */
internal class RecordingMemory(byteSize: Long, private val owner: RecordingScratch) : Memory(byteSize) {
    private val length: Int = byteSize.toInt()
    @Volatile var wipedAtClose: Boolean = false
    private val closes: AtomicInteger = AtomicInteger()

    override fun close() {
        if (closes.incrementAndGet() == 1) {
            wipedAtClose = getByteArray(0L, length).all { it == 0.toByte() }
            owner.closed.incrementAndGet()
        }
        super.close()
    }

    val closeCount: Int get() = closes.get()
}

/**
 * A [ScratchMemory] that hands out [RecordingMemory] and counts allocations and releases: the leak proof of every
 * native block this binding makes (the control assertion is [allocated] being positive).
 */
internal class RecordingScratch : ScratchMemory {
    val blocks: CopyOnWriteArrayList<RecordingMemory> = CopyOnWriteArrayList()
    val closed: AtomicInteger = AtomicInteger()
    val allocated: Int get() = blocks.size

    override fun allocate(size: Long): Memory {
        val block = RecordingMemory(size, this)
        blocks.add(block)
        return block
    }

    /** Every block was freed exactly once, and every one of them was zero when it was freed. */
    fun assertAllFreedAndWiped(message: String) {
        assertTrue(allocated > 0, "$message: control, the call must have allocated native scratch memory")
        assertEquals(allocated, closed.get(), "$message: every native block must be freed")
        for (block: RecordingMemory in blocks) {
            assertEquals(1, block.closeCount, "$message: a block must be freed exactly once")
            assertTrue(block.wipedAtClose, "$message: a native block must be zeroed before it is freed")
        }
    }
}

/** The text of the NUL-terminated string at [pointer]. */
internal fun cString(pointer: Pointer?): String? = pointer?.getString(0L, "UTF-8")

/** A thread-safe set of fake native handles with a live count: what a call leaves behind is [live]. */
internal class HandleTable<T : Any>(private val firstPeer: Long) {
    private val next: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(firstPeer)
    private val handles: ConcurrentHashMap<Long, T> = ConcurrentHashMap()
    val created: AtomicInteger = AtomicInteger()
    val released: AtomicInteger = AtomicInteger()
    val live: Int get() = handles.size

    fun create(value: T): Pointer {
        val peer: Long = next.getAndAdd(16L)
        handles[peer] = value
        created.incrementAndGet()
        return Pointer(peer)
    }

    fun get(pointer: Pointer): T = handles[Pointer.nativeValue(pointer)] ?: error("unknown or already released handle ${Pointer.nativeValue(pointer)}")

    fun peek(pointer: Pointer): T? = handles[Pointer.nativeValue(pointer)]

    fun release(pointer: Pointer): T {
        val value: T = handles.remove(Pointer.nativeValue(pointer)) ?: error("double release or unknown handle ${Pointer.nativeValue(pointer)}")
        released.incrementAndGet()
        return value
    }
}
