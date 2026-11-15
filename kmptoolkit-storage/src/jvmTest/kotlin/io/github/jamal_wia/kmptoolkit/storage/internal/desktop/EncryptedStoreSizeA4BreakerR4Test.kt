package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, phase A breaker round 4 (decisions.md D-23): the round-5 size check by counting
 * (`strictUtf8Length`, [SecureFileFormat.entrySize]) against the bytes that really land on disk, at the exact boundary.
 * Plan 4.11: a store file is at most the limit, and the read side refuses only a file LARGER than the limit, so a file of
 * exactly the limit must be written AND read back (an off-by-one between the counted size and the written size, or between
 * the write check and the read check, would make the store quarantine its own file on the next call). Four-byte characters
 * (surrogate pairs) and a multi-byte entry name make the char count and the byte count disagree on purpose.
 */
class EncryptedStoreSizeA4BreakerR4Test {

    private fun <T> withRig(block: suspend SecureTestRig.() -> T): T =
        SecureTestRig().use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    @Test
    fun `a value that makes the file exactly the limit is stored and read back, one byte more is refused and changes nothing`() = withRig {
        val limit = 4_096L
        val store: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(Unit), store.put("a", "x"))
        val withFirst: Long = dataFile().length()
        val key = "é😀"
        val bytes: Long = limit - withFirst - SecureFileFormat.entrySize(key, 0)
        val value: String = "😀".repeat((bytes / 4).toInt()) + "x".repeat((bytes % 4).toInt())
        assertEquals(bytes, value.strictUtf8Length(), "control: the value is $bytes UTF-8 bytes")

        assertEquals(StorageResult.Success(Unit), store.put(key, value), "a value that makes the file exactly $limit bytes was refused")

        assertEquals(limit, dataFile().length(), "the counted size and the written size disagree")
        val reopened: JvmSecureKeyValueStorage = store(maxStoreBytes = limit)
        assertEquals(StorageResult.Success(value), reopened.get(key), "a file of exactly the limit cannot be read back")
        assertEquals(StorageResult.Success("x"), reopened.get("a"))

        // Replacing the value with one of the same size: the old entry is not counted twice.
        val same: String = "😀".repeat((bytes / 4).toInt()) + "y".repeat((bytes % 4).toInt())
        assertEquals(StorageResult.Success(Unit), store.put(key, same), "a same-size replacement at the limit was refused")
        assertEquals(limit, dataFile().length())
        val before: ByteArray = dataFile().readBytes()

        val refused: StorageResult<Unit> = store.put(key, same + "z")

        val error: StorageError = assertIs<StorageResult.Failure>(refused, "one byte over the limit was accepted").error
        assertEquals(key, assertIs<StorageError.InvalidValue>(error).key)
        assertContentEquals(before, dataFile().readBytes(), "the refused put changed the file")
        assertEquals(StorageResult.Success(same), store(maxStoreBytes = limit).get(key))
    }

    private companion object {
        val HANG_BOUND = 60.seconds
    }
}
