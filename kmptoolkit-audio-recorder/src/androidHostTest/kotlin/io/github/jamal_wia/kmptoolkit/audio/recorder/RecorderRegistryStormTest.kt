package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resource starvation under real threads: pairs of recorders share one explicit path and are driven,
 * released and hit by system events at random instants. Whatever the interleaving, once both are
 * released and their finalizations are over the process-wide path registry must hold nothing of the
 * round, otherwise every such race leaks an entry for good.
 */
class RecorderRegistryStormTest {

    private class SafeEngine : RecorderEngine {
        val listener: AtomicReference<((EngineEvent) -> Unit)?> = AtomicReference(null)
        private fun spin() = LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 200_000))
        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true
        override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) = spin()
        override fun setEventListener(listener: ((EngineEvent) -> Unit)?) {
            this.listener.set(listener)
        }
        override fun start() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun peakDbfs(): Float? = null
        override fun stop() = spin()
        override fun release() = Unit
    }

    private class SafeFileSystem : RecordingFileSystem {
        val existing: MutableSet<String> = ConcurrentHashMap.newKeySet()
        override fun appPrivateDirectory(): String = "/rstorm"
        override fun applicationIdentifier(): String = "app"
        override fun resolve(directory: String, name: String): String = "$directory/$name"
        override fun parentOf(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        override fun ensureWritableDirectory(path: String): Boolean = true
        override fun freeSpaceBytes(path: String): Long = Long.MAX_VALUE
        override fun exists(path: String): Boolean = path in existing
        override fun delete(path: String) {
            existing.remove(path)
        }
    }

    private fun ownKeys(prefix: String): List<String> {
        val field = OutputPathOwners::class.java.getDeclaredField("slots")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(OutputPathOwners) as MutableStateFlow<Map<String, Any>>
        return flow.value.keys.filter { it.startsWith(prefix) }
    }

    private suspend fun drive(recorder: AudioRecorder, path: String, seed: Long) {
        val random = java.util.Random(seed)
        val cancelAfter: Long = if (random.nextInt(4) == 0) random.nextInt(300_000).toLong() else Long.MAX_VALUE
        val prepared = withTimeoutOrNull(if (cancelAfter == Long.MAX_VALUE) 20_000 else 1) {
            if (cancelAfter != Long.MAX_VALUE) LockSupport.parkNanos(cancelAfter)
            recorder.prepare(path)
        } ?: return
        if (prepared !is RecorderResult.Success<*>) return
        if (random.nextInt(5) == 0) return
        recorder.start()
        if (random.nextBoolean()) {
            recorder.pause()
            recorder.resume()
        }
        when (random.nextInt(3)) {
            0 -> recorder.stop()
            1 -> recorder.cancel()
            else -> Unit
        }
    }

    @Test
    fun `racing recorders sharing a path never leave an entry in the path registry`() {
        val prefix = "/rstorm/"
        runBlocking {
            withTimeout(90_000) {
                repeat(ROUNDS) { round: Int ->
                    val path = "$prefix$round.m4a"
                    val fileSystem = SafeFileSystem()
                    val engineA = SafeEngine()
                    val engineB = SafeEngine()
                    fun newRecorder(engine: SafeEngine): DefaultAudioRecorder = DefaultAudioRecorder(
                        engine = engine,
                        fileSystem = fileSystem,
                        config = AudioRecorderConfig(),
                        workerContext = Dispatchers.Default,
                        epochClock = EpochClock { 1L },
                    )
                    val a = newRecorder(engineA)
                    val b = newRecorder(engineB)
                    val random = java.util.Random(77L * round)
                    withContext(Dispatchers.Default) {
                        listOf(
                            async { drive(a, path, random.nextLong()) },
                            async { drive(b, path, random.nextLong()) },
                            async { LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 400_000)); a.release() },
                            async { LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 400_000)); b.release() },
                            async {
                                LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 400_000))
                                engineA.listener.get()?.invoke(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
                            },
                        ).awaitAll()
                    }
                    a.release()
                    b.release()
                    var attempts = 0
                    while ((a.isFinalizing || b.isFinalizing) && attempts++ < 500) {
                        withContext(Dispatchers.Default) { LockSupport.parkNanos(1_000_000) }
                    }
                    assertTrue(!a.isFinalizing && !b.isFinalizing, "round $round: a finalization never ended")
                    assertEquals(emptyList(), ownKeys(path), "round $round leaked a registry entry (a=${a.state.value}, b=${b.state.value})")
                }
            }
        }
    }

    private companion object {
        const val ROUNDS: Int = 250
    }
}
