package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * A post is in flight from the moment `post` is called. A cancel that runs after that, before the
 * coalescer has heard of the post through `prepare`, must still say no to it.
 */
class ProgressCoalescerClaimAdversarialTest {

    private fun coalescer(): ProgressCoalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
    private val progress = NotificationProgress.Determinate(40)

    @Test
    fun `a post claimed before a forget is dropped by prepare`() {
        val coalescer: ProgressCoalescer = coalescer()
        val claim: ProgressCoalescer.PostClaim = coalescer.claim("x")
        coalescer.forget("x")

        assertNull(coalescer.prepare("x", progress, "c", claim))
    }

    @Test
    fun `a post claimed before a clear is dropped by prepare`() {
        val coalescer: ProgressCoalescer = coalescer()
        val claim: ProgressCoalescer.PostClaim = coalescer.claim("x")
        coalescer.clear()

        assertNull(coalescer.prepare("x", progress, "c", claim))
    }

    @Test
    fun `a forget of another id does not drop the post`() {
        val coalescer: ProgressCoalescer = coalescer()
        val claim: ProgressCoalescer.PostClaim = coalescer.claim("x")
        coalescer.forget("y")

        assertNotNull(coalescer.prepare("x", progress, "c", claim))
    }

    @Test
    fun `a claim taken after a forget is not dropped`() {
        val coalescer: ProgressCoalescer = coalescer()
        coalescer.forget("x")
        val claim: ProgressCoalescer.PostClaim = coalescer.claim("x")

        assertNotNull(coalescer.prepare("x", progress, "c", claim))
    }

    @Test
    fun `a cancelled terminal frame is dropped too`() {
        val coalescer: ProgressCoalescer = coalescer()
        val claim: ProgressCoalescer.PostClaim = coalescer.claim("x")
        coalescer.forget("x")

        assertNull(coalescer.prepare("x", null, "c", claim))
    }

    @Test
    fun `a released claim leaves nothing behind`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostClaim = coalescer.claim("x")
        val second: ProgressCoalescer.PostClaim = coalescer.claim("x")
        val other: ProgressCoalescer.PostClaim = coalescer.claim("y")
        coalescer.release(first)
        assertEquals(2, coalescer.claimCountForTest())
        coalescer.release(second)
        coalescer.release(other)
        coalescer.release(other)

        assertEquals(0, coalescer.claimCountForTest())
    }

    @Test
    fun `a forget drops every claim of the id and releasing afterwards is harmless`() {
        val coalescer: ProgressCoalescer = coalescer()
        val first: ProgressCoalescer.PostClaim = coalescer.claim("x")
        val second: ProgressCoalescer.PostClaim = coalescer.claim("x")
        coalescer.forget("x")

        assertEquals(0, coalescer.claimCountForTest())
        coalescer.release(first)
        coalescer.release(second)
        assertEquals(0, coalescer.claimCountForTest())
    }
}
