package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * `AVAudioSession` is one per process, so one recorder letting go of it must not deactivate it
 * under another that is still recording — iOS stops every running audio object of a session it
 * deactivates. The real session cannot be driven from a test, so these count against a fake one;
 * the engine tests use real [AvAudioRecorderEngine]s wired to it.
 */
class AudioSessionHoldersTest {

    private class FakeSession : AudioSessionControl {
        var activations: Int = 0
        var deactivations: Int = 0
        var failActivation: Boolean = false

        override fun activate() {
            if (failActivation) error("session refused")
            activations++
        }

        override fun deactivate() {
            deactivations++
        }
    }

    private val session = FakeSession()
    private val holders = AudioSessionHolders(session)

    private fun outputPath(): String = NSTemporaryDirectory() + NSUUID().UUIDString + ".m4a"

    // --- the count ---

    @Test
    fun `the session stays active until the last holder releases it`() {
        holders.acquire()
        holders.acquire()

        holders.release()
        assertEquals(0, session.deactivations, "one holder letting go deactivated the other's session")

        holders.release()
        assertEquals(1, session.deactivations)
    }

    @Test
    fun `every acquire activates so a session the system deactivated meanwhile comes back`() {
        holders.acquire()
        holders.acquire()

        assertEquals(2, session.activations)
    }

    @Test
    fun `a failed activation is not counted as a hold`() {
        holders.acquire()
        session.failActivation = true

        assertFailsWith<IllegalStateException> { holders.acquire() }
        assertEquals(1, holders.count)

        holders.release()
        assertEquals(1, session.deactivations, "the only real holder letting go must deactivate")
    }

    @Test
    fun `an unbalanced release is ignored rather than deactivating or throwing`() {
        holders.release()

        assertEquals(0, session.deactivations)
        assertEquals(0, holders.count)
    }

    // --- engines sharing the count ---

    @Test
    fun `releasing one engine leaves the session active for another engine still holding it`() = runTest {
        // Stands in for a second recorder that is recording: the simulator host cannot prepare a
        // real AVAudioRecorder, so its hold is taken directly.
        holders.acquire()
        val other = AvAudioRecorderEngine(audioSession = holders)

        // prepare() fails in the simulator host (no microphone) and gives its hold back on the way
        // out; on a host where it succeeds, release() gives it back below. Either way the session
        // must survive while the first holder still needs it.
        runCatching { other.prepare(outputPath(), AudioRecorderConfig(), null) }
        other.release()

        assertEquals(0, session.deactivations, "another recorder's release deactivated a live recording's session")
        assertEquals(1, holders.count)

        holders.release()
        assertEquals(1, session.deactivations)
    }

    @Test
    fun `an engine whose activation fails gives back nothing it did not take`() = runTest {
        holders.acquire()
        session.failActivation = true
        val engine = AvAudioRecorderEngine(audioSession = holders)

        assertFailsWith<IllegalStateException> { engine.prepare(outputPath(), AudioRecorderConfig(), null) }
        engine.release()

        assertEquals(1, holders.count, "a hold the engine never took was given back")
        assertEquals(0, session.deactivations)
    }

    @Test
    fun `an engine released twice gives back its hold once`() = runTest {
        holders.acquire()
        val engine = AvAudioRecorderEngine(audioSession = holders)
        runCatching { engine.prepare(outputPath(), AudioRecorderConfig(), null) }

        engine.release()
        engine.release()

        assertEquals(1, holders.count)
        assertEquals(0, session.deactivations)
    }
}
