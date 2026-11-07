package io.github.jamal_wia.kmptoolkit.audio.recorder

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * GC reachability: a released recorder and its engine must become collectable whatever the
 * recorder was doing, because release is the one documented way to dispose of it.
 */
class RecorderReachabilityTest {

    @Test
    fun `a released recorder and its engine become unreachable after every kind of ending`() {
        val refs: MutableList<WeakReference<Any>> = mutableListOf()
        repeat(40) { round: Int ->
            val engine = FakeRecorderEngine()
            val recorder = DefaultAudioRecorder(
                engine = engine,
                fileSystem = FakeRecordingFileSystem(),
                config = AudioRecorderConfig(),
                workerContext = kotlinx.coroutines.Dispatchers.Unconfined,
                epochClock = EpochClock { 1L },
            )
            runBlocking {
                withTimeout(5_000) {
                    recorder.prepare("/gc/r$round.m4a")
                    if (round % 4 != 0) recorder.start()
                    // collectors that come and go
                    val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { recorder.level.collect { } }
                    when (round % 4) {
                        1 -> recorder.stop()
                        2 -> recorder.cancel()
                        3 -> engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
                        else -> Unit
                    }
                    job.cancel()
                }
            }
            recorder.release()
            refs += WeakReference(recorder)
            refs += WeakReference(engine)
        }
        var alive: Int = refs.size
        for (attempt in 0 until 50) {
            System.gc()
            System.runFinalization()
            alive = refs.count { it.get() != null }
            if (alive == 0) break
            Thread.sleep(40)
        }
        assertEquals(0, alive, "recorders or engines still reachable after release")
    }
}
