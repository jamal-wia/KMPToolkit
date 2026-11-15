package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 1 of group files-owner-only (STO-1, storage PLAN 3.7/T10, `FakeFileAccess.isOwnerOnly`): the fake
 * records the owner-only flag per entry on the inode. Under a storm of writers on real threads the flag of every file
 * must be the flag of the write whose content it holds (a rename carries the inode, a hard link shares it), a directory
 * only ever created owner-only answers true and one only ever created plain answers false, and the query answers for the
 * same spellings the rest of the fake accepts (case variants on a case-insensitive fake, a trailing separator).
 */
class FakeOwnerOnlyBreakerR1Test {

    private suspend fun storm(fake: FakeFileAccess, seed: Int, workers: Int, steps: Int): List<String> =
        withContext(Dispatchers.Default) {
            withTimeout(60.seconds) {
                coroutineScope {
                    List(workers) { worker: Int ->
                        async {
                            val problems: MutableList<String> = ArrayList()
                            val random = Random(seed * 1000 + worker)
                            repeat(steps) { step: Int ->
                                when (random.nextInt(4)) {
                                    0 -> {
                                        val ownerOnly: Boolean = random.nextBoolean()
                                        val dir: String = if (ownerOnly) "/o${random.nextInt(3)}/x" else "/p${random.nextInt(3)}/x"
                                        val made: FileResult<Unit> = fake.createDirectories(dir, ownerOnly = ownerOnly)
                                        if (!made.isSuccess) problems += "seed=$seed worker $worker step $step: $dir $made"
                                    }
                                    else -> {
                                        val ownerOnly: Boolean = random.nextBoolean()
                                        val existing: ExistingFile = if (random.nextInt(5) == 0) ExistingFile.Fail else ExistingFile.Replace
                                        val path = "/d/f${random.nextInt(3)}"
                                        val content: ByteArray = "${if (ownerOnly) 'O' else 'P'}$worker-$step".encodeToByteArray()
                                        val written: FileResult<Unit> = fake.writeAtomically(
                                            path,
                                            content,
                                            WriteOptions(Durability.Buffered, existing = existing, createParentDirectories = true, ownerOnly = ownerOnly),
                                        )
                                        val refused: Boolean = existing == ExistingFile.Fail && written is FileResult.Failure && written.error is FileError.AlreadyExists
                                        if (!written.isSuccess && !refused) problems += "seed=$seed worker $worker step $step: $path $written"
                                    }
                                }
                            }
                            problems
                        }
                    }.awaitAll().flatten()
                }
            }
        }

    private fun verifyQuiescent(fake: FakeFileAccess, seed: Int) {
        val entries: Map<String, FakeEntry> = fake.entries()
        val files: List<String> = entries.filterValues { it is FakeEntry.File }.keys.toList()
        assertTrue(files.isNotEmpty(), "seed=$seed: control, the storm wrote files")
        for (path: String in files) {
            assertTrue(path.startsWith("/d/f"), "seed=$seed: a temp file was left: $path in ${entries.keys}")
            val content: String = fake.readBytes(path, 1024).getOrNull()!!.decodeToString()
            assertEquals(content.startsWith("O"), fake.isOwnerOnly(path), "seed=$seed: $path holds '$content'")
        }
        for (path: String in entries.keys) {
            if (path.startsWith("/o")) assertTrue(fake.isOwnerOnly(path), "seed=$seed: $path was only ever created owner-only")
            if (path.startsWith("/p")) assertFalse(fake.isOwnerOnly(path), "seed=$seed: $path was only ever created plain")
        }
    }

    @Test
    fun `under a storm the flag of every file is the flag of the write whose content it holds`() = runTest(timeout = 90.seconds) {
        val seed = 2201
        val fake = FakeFileAccess()
        val problems: List<String> = storm(fake, seed, workers = 12, steps = 150)
        assertEquals(listOf(), problems, "seed=$seed")
        verifyQuiescent(fake, seed)
    }

    @Test
    fun `the same holds on a case-insensitive fake`() = runTest(timeout = 90.seconds) {
        val seed = 2202
        val fake = FakeFileAccess(caseSensitive = false)
        val problems: List<String> = storm(fake, seed, workers = 12, steps = 150)
        assertEquals(listOf(), problems, "seed=$seed")
        verifyQuiescent(fake, seed)
    }

    @Test
    fun `isOwnerOnly answers for a case variant on a case-insensitive fake and for a trailing separator`() {
        val fake = FakeFileAccess(caseSensitive = false)
        assertTrue(fake.createDirectories("/Secret/Dir", ownerOnly = true).isSuccess)
        assertTrue(fake.writeAtomically("/Secret/Dir/Key", byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(fake.isOwnerOnly("/secret/dir/key"), "a case variant names the same file")
        assertTrue(fake.isOwnerOnly("/SECRET"), "a case variant names the same directory")
        assertTrue(fake.isOwnerOnly("/Secret/Dir/"), "a trailing separator names the same directory, as everywhere in FileAccess")
        val sensitive = FakeFileAccess()
        assertTrue(sensitive.createDirectories("/a/b/", ownerOnly = true).isSuccess)
        assertTrue(sensitive.isOwnerOnly("/a/b/"), "the spelling the write accepted is accepted by the query")
    }

    @Test
    fun `a failed owner-only write leaves no flagged temp behind and a later plain write is not flagged`() {
        val fake = FakeFileAccess()
        fake.createDirectory("/d")
        fake.failNext(FakeOperation.Rename, FileError.Io("/d", null, null))
        val failed: FileResult<Unit> = fake.writeAtomically("/d/f", byteArrayOf(1), WriteOptions(ownerOnly = true))
        assertTrue(failed is FileResult.Failure, "$failed")
        assertEquals(listOf("/d"), fake.entries().keys.toList(), "no temp file is left")
        assertTrue(fake.writeAtomically("/d/f", byteArrayOf(2)).isSuccess)
        assertFalse(fake.isOwnerOnly("/d/f"), "a plain write after the failed owner-only one is not flagged")
    }
}
