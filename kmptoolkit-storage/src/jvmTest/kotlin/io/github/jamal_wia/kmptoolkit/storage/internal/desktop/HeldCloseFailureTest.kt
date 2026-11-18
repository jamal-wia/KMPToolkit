package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Breaker round 1, finding m4 (group sto-desktop): `Held.close()` runs inside a store call's `use {}` outside the typed
 * result, so a channel whose `close()` throws must never make the call throw, and the in-process claim must still be
 * released (otherwise the lock file is busy for the rest of the process).
 */
class HeldCloseFailureTest {

    private class FailingChannel : FileChannel() {
        val closeAttempts: AtomicInteger = AtomicInteger()

        override fun implCloseChannel() {
            closeAttempts.incrementAndGet()
            throw IOException("close failed (test)")
        }

        private fun nope(): Nothing = throw UnsupportedOperationException("not used by Held")
        override fun read(dst: ByteBuffer): Int = nope()
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = nope()
        override fun write(src: ByteBuffer): Int = nope()
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = nope()
        override fun position(): Long = nope()
        override fun position(newPosition: Long): FileChannel = nope()
        override fun size(): Long = nope()
        override fun truncate(size: Long): FileChannel = nope()
        override fun force(metaData: Boolean) = nope()
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = nope()
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = nope()
        override fun read(dst: ByteBuffer, position: Long): Int = nope()
        override fun write(src: ByteBuffer, position: Long): Int = nope()
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = nope()
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = nope()
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = nope()
    }

    private class FakeLock(channel: FileChannel, private val releaseFails: Boolean) : FileLock(channel, 0, 1, false) {
        val releases: AtomicInteger = AtomicInteger()
        override fun isValid(): Boolean = releases.get() == 0
        override fun release() {
            releases.incrementAndGet()
            if (releaseFails) throw IOException("release failed (test)")
        }
    }

    private fun closeWith(releaseFails: Boolean) {
        val channel = FailingChannel()
        val lock = FakeLock(channel, releaseFails)
        val claims = AtomicInteger(1) // control: the claim exists before the close
        val held = LockAcquisition.Held(channel, lock) { claims.decrementAndGet() }
        assertEquals(1, claims.get(), "control: the claim is held")

        held.close() // must not throw

        assertEquals(1, lock.releases.get(), "the lock was released")
        assertEquals(1, channel.closeAttempts.get(), "the channel close was attempted")
        assertEquals(0, claims.get(), "the claim is released although the channel close threw")
        held.close()
        assertEquals(1, lock.releases.get(), "a second close does nothing")
        assertEquals(0, claims.get(), "a second close does not release the claim twice")
    }

    @Test
    fun `a channel whose close throws does not make Held close throw and still releases the claim`() = closeWith(releaseFails = false)

    @Test
    fun `a lock whose release throws and a channel whose close throws still release the claim`() = closeWith(releaseFails = true)
}
