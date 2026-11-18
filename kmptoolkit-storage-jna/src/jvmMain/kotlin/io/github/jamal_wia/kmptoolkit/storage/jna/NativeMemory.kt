package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Memory

/**
 * Where this binding allocates the native scratch memory it passes to the operating system. A seam for tests only: they
 * hand out a recording [Memory] to prove that every native copy of a key is zeroed and then freed.
 */
internal fun interface ScratchMemory {
    /** A fresh native block of at least [size] bytes (a block cannot be empty). */
    fun allocate(size: Long): Memory

    companion object {
        /** The production allocator: plain JNA [Memory]. */
        val Default: ScratchMemory = ScratchMemory { size: Long -> Memory(size) }
    }
}

/**
 * Copies [bytes] (plus [terminator] zero bytes) into a native block, runs [block] with it, and always zeroes and frees the
 * block afterwards, also when [block] throws. The block holds the only native copy of a key this binding makes.
 */
internal inline fun <T> ScratchMemory.withCopy(bytes: ByteArray, terminator: Int = 0, block: (Memory) -> T): T {
    val memory: Memory = allocate(maxOf(1L, bytes.size.toLong() + terminator))
    try {
        memory.clear()
        if (bytes.isNotEmpty()) memory.write(0L, bytes, 0, bytes.size)
        return block(memory)
    } finally {
        memory.clear()
        memory.close()
    }
}

/** The bytes of [text] in UTF-8, as the operating-system calls expect them. */
internal fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
