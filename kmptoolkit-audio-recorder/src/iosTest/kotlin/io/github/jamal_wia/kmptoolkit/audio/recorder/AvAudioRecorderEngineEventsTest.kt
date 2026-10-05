package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.numberWithUnsignedInteger

/**
 * What [AvAudioRecorderEngine] reports on its own, driven the way the platform would: notifications
 * posted through `NSNotificationCenter` with the same `userInfo` shape iOS uses, and the delegate's
 * methods called directly. Capturing audio needs a microphone and is left to a device; none of this
 * does.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AvAudioRecorderEngineEventsTest {

    // The simulator host cannot prepare an AVAudioRecorder (prepareToRecord fails without a
    // microphone), so the engine never holds one and its own identity check would refuse every
    // callback. These tests drive the delegate with recorders of their own and accept them all;
    // what the default check refuses is tested separately below.
    private val engine = AvAudioRecorderEngine(delegateAccepts = { _, _ -> true })
    private val events: MutableList<EngineEvent> = mutableListOf()

    @AfterTest
    fun tearDown() {
        engine.release()
    }

    private fun postInterruption(type: ULong?) {
        val userInfo: Map<Any?, Any> =
            if (type == null) emptyMap() else mapOf(AVAudioSessionInterruptionTypeKey to NSNumber.numberWithUnsignedInteger(type))
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = AVAudioSessionInterruptionNotification,
            `object` = AVAudioSession.sharedInstance(),
            userInfo = userInfo,
        )
    }

    private fun postMediaServicesReset() {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = AVAudioSessionMediaServicesWereResetNotification,
            `object` = AVAudioSession.sharedInstance(),
            userInfo = null,
        )
    }

    private fun newRecorder(): AVAudioRecorder = memScoped {
        val errorRef = alloc<ObjCObjectVar<NSError?>>()
        val recorder = AVAudioRecorder(
            uRL = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + ".m4a"),
            settings = mapOf<Any?, Any>(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVSampleRateKey to 44_100.0,
                AVNumberOfChannelsKey to 1,
            ),
            error = errorRef.ptr,
        )
        check(errorRef.value == null) { "test recorder: ${errorRef.value?.localizedDescription}" }
        recorder
    }

    // --- mapping ---

    @Test
    fun `an interruption that began is the audio session being taken`() {
        assertEquals(
            InterruptionReason.AudioSessionInterrupted,
            mapAudioSessionInterruption(AVAudioSessionInterruptionTypeBegan),
        )
    }

    @Test
    fun `an interruption that ended or has no type ends nothing`() {
        assertNull(mapAudioSessionInterruption(AVAudioSessionInterruptionTypeEnded))
        assertNull(mapAudioSessionInterruption(null))
    }

    // --- notifications ---

    @Test
    fun `an interruption notification reaches the listener`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        postInterruption(AVAudioSessionInterruptionTypeBegan)

        assertContentEquals(
            listOf(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted)),
            events,
        )
    }

    @Test
    fun `the end of an interruption is ignored because nothing resumes by itself`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        postInterruption(AVAudioSessionInterruptionTypeEnded)
        postInterruption(null)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a media services reset is an engine death`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        postMediaServicesReset()

        assertContentEquals(listOf(EngineEvent.Interrupted(InterruptionReason.EngineDied())), events)
    }

    @Test
    fun `removing the listener removes the observers`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener(null)

        postInterruption(AVAudioSessionInterruptionTypeBegan)
        postMediaServicesReset()

        assertTrue(events.isEmpty(), "a notification after removal must not reach anyone")
    }

    @Test
    fun `release removes the observers`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.release()
        postInterruption(AVAudioSessionInterruptionTypeBegan)
        postMediaServicesReset()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `setting the listener twice registers each observer once`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener { event: EngineEvent -> events += event }

        postInterruption(AVAudioSessionInterruptionTypeBegan)

        assertEquals(1, events.size, "a duplicate observer would report the same event twice")
    }

    @Test
    fun `observers are registered when a listener is set and gone after it is removed`() {
        assertEquals(0, engine.observerCount)

        engine.setEventListener { event: EngineEvent -> events += event }
        assertEquals(2, engine.observerCount, "one for interruptions, one for a media services reset")

        engine.setEventListener(null)
        assertEquals(0, engine.observerCount, "every observer is removed, not merely muted")
    }

    @Test
    fun `release removes every observer`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        assertEquals(2, engine.observerCount)

        engine.release()

        assertEquals(0, engine.observerCount)
    }

    @Test
    fun `replacing the listener leaves exactly one set of observers`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener { event: EngineEvent -> events += event }

        assertEquals(2, engine.observerCount)
    }

    @Test
    fun `a notification observed for a replaced listener never reaches the new one`() {
        val first: MutableList<EngineEvent> = mutableListOf()
        engine.setEventListener { event: EngineEvent -> first += event }
        engine.setEventListener { event: EngineEvent -> events += event }

        postInterruption(AVAudioSessionInterruptionTypeBegan)

        assertTrue(first.isEmpty(), "the replaced listener hears nothing more")
        assertEquals(1, events.size)
    }

    @Test
    fun `a delegate bound to a replaced listener reaches nobody`() {
        val first: MutableList<EngineEvent> = mutableListOf()
        engine.setEventListener { event: EngineEvent -> first += event }
        val stale: RecorderDelegate = engine.recorderDelegate
        engine.setEventListener { event: EngineEvent -> events += event }

        stale.audioRecorderDidFinishRecording(newRecorder(), successfully = false)

        assertTrue(first.isEmpty())
        assertTrue(events.isEmpty(), "a callback queued for the old listener must not reach the new one")
    }

    @Test
    fun `a callback about a recorder the engine does not hold is ignored`() {
        // The default identity check: this engine never prepared, so it holds no recorder and any
        // recorder's late callback is one of an earlier, replaced session.
        val strict = AvAudioRecorderEngine()
        strict.setEventListener { event: EngineEvent -> events += event }
        try {
            strict.recorderDelegate.audioRecorderDidFinishRecording(newRecorder(), successfully = false)
            strict.recorderDelegate.audioRecorderEncodeErrorDidOccur(newRecorder(), null)

            assertTrue(events.isEmpty())
        } finally {
            strict.release()
        }
    }

    @Test
    fun `releasing an engine that never prepared is safe`() {
        engine.release()
        engine.release()
    }

    // --- delegate ---

    @Test
    fun `an encode error is an engine death carrying the error code`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        val error = NSError.errorWithDomain("test", code = -42, userInfo = null)

        engine.recorderDelegate.audioRecorderEncodeErrorDidOccur(newRecorder(), error)

        assertContentEquals(listOf(EngineEvent.Interrupted(InterruptionReason.EngineDied(-42))), events)
    }

    @Test
    fun `an encode error without an error object is an engine death without a code`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.recorderDelegate.audioRecorderEncodeErrorDidOccur(newRecorder(), null)

        assertContentEquals(listOf(EngineEvent.Interrupted(InterruptionReason.EngineDied())), events)
    }

    @Test
    fun `a recording that finished unsuccessfully is an engine death`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.recorderDelegate.audioRecorderDidFinishRecording(newRecorder(), successfully = false)

        assertContentEquals(listOf(EngineEvent.Interrupted(InterruptionReason.EngineDied())), events)
    }

    @Test
    fun `a recording that finished successfully ends nothing`() {
        engine.setEventListener { event: EngineEvent -> events += event }

        engine.recorderDelegate.audioRecorderDidFinishRecording(newRecorder(), successfully = true)

        assertTrue(events.isEmpty(), "didFinishRecording(true) follows our own stop() and means nothing")
    }

    @Test
    fun `delegate callbacks after the listener was removed reach nobody`() {
        engine.setEventListener { event: EngineEvent -> events += event }
        engine.setEventListener(null)

        engine.recorderDelegate.audioRecorderDidFinishRecording(newRecorder(), successfully = false)
        engine.recorderDelegate.audioRecorderEncodeErrorDidOccur(newRecorder(), null)

        assertTrue(events.isEmpty())
    }
}
