package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A `failNext` path filter is consumer code: it runs outside the fake's lock, so a blocking one cannot deadlock it. */
class FakeFilterThreadingTest {
    @Test
    fun `a blocking path filter does not deadlock the fake`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createFile("/d/a", ByteArray(1))
        val inFilter: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        fake.failNext(FakeOperation.OpenForRead, FileError.AccessDenied("/d/a"), path = {
            inFilter.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the other thread never got through" }
            true
        })
        val outcome: AtomicReference<FileResult<ByteArray>?> = AtomicReference(null)
        val reader: Thread = thread { outcome.set(fake.readBytes("/d/a", 10)) }
        assertTrue(inFilter.await(10, TimeUnit.SECONDS), "the filter was never reached")
        // The first thread is inside the filter; with the lock held there this call would wait for it forever.
        val metadataDone: CountDownLatch = CountDownLatch(1)
        thread {
            fake.metadata("/d/a")
            metadataDone.countDown()
        }
        assertTrue(metadataDone.await(10, TimeUnit.SECONDS), "the fake must stay usable while a filter blocks")
        release.countDown()
        reader.join(10_000)
        assertEquals(FileError.AccessDenied("/d/a"), outcome.get()?.errorOrNull())
    }

    @Test
    fun `two threads racing for one fault count consume it once`() {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createFile("/d/a", ByteArray(1))
        fake.failNext(FakeOperation.OpenForRead, FileError.AccessDenied("/d/a"), count = 1)
        val results: Array<FileResult<ByteArray>?> = arrayOfNulls(8)
        val threads: List<Thread> = List(8) { index -> thread { results[index] = fake.readBytes("/d/a", 10) } }
        threads.forEach { it.join(10_000) }
        assertEquals(1, results.count { it?.errorOrNull() is FileError.AccessDenied }, "exactly one reader gets the fault")
    }
}
