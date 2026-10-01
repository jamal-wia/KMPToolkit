package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.coroutines.CompletableDeferred

/**
 * A scriptable stand-in for `MediaRecorder` / `AVAudioRecorder`.
 *
 * It records the calls it received so a test can assert that the state machine actually reached
 * the platform (and, just as importantly, that it did not — a rejected transition must never touch
 * the engine), and it can be told to throw from any single call.
 */
internal class FakeRecorderEngine : RecorderEngine {

    var permissionGranted: Boolean = true
    var unsupportedFormats: Set<AudioFormat> = emptySet()

    /** Throwable to raise from the named operation, keyed by the [RecorderOperation] it maps to. */
    val failures: MutableMap<RecorderOperation, Throwable> = mutableMapOf()

    /** Set to make `prepare` suspend until completed, so a test can cancel mid-preparation. */
    var prepareGate: CompletableDeferred<Unit>? = null

    /**
     * What each `peakDbfs()` call answers, consumed in order; once empty, [defaultPeak] is the
     * answer. `null` entries model a platform that could not answer.
     */
    val peakSamples: ArrayDeque<Float?> = ArrayDeque()

    /** Answer once [peakSamples] is exhausted. Digital silence unless a test says otherwise. */
    var defaultPeak: Float? = Float.NEGATIVE_INFINITY

    /**
     * Runs inside every `peakDbfs()` call, before it answers, so a test can land a transition
     * exactly while a sample is being taken — the race the metering coroutine has to survive.
     */
    var onPeak: () -> Unit = {}

    /** How many times `peakDbfs()` was called, priming call included. */
    var peakCalls: Int = 0
        private set

    val calls: MutableList<String> = mutableListOf()
    var releaseCount: Int = 0
        private set

    override fun hasRecordAudioPermission(): Boolean {
        calls += "hasPermission"
        return permissionGranted
    }

    override fun supportsFormat(format: AudioFormat): Boolean = format !in unsupportedFormats

    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig) {
        calls += "prepare($outputPath)"
        prepareGate?.await()
        failures[RecorderOperation.PREPARE]?.let { throw it }
    }

    override fun start() {
        calls += "start"
        failures[RecorderOperation.START]?.let { throw it }
    }

    override fun pause() {
        calls += "pause"
        failures[RecorderOperation.PAUSE]?.let { throw it }
    }

    override fun resume() {
        calls += "resume"
        failures[RecorderOperation.RESUME]?.let { throw it }
    }

    override fun peakDbfs(): Float? {
        // Deliberately not in `calls`: it ticks every interval and would bury the lifecycle calls
        // the existing tests assert on; peakCalls counts it instead.
        peakCalls++
        onPeak()
        return if (peakSamples.isEmpty()) defaultPeak else peakSamples.removeFirst()
    }

    override fun stop() {
        calls += "stop"
        failures[RecorderOperation.STOP]?.let { throw it }
    }

    override fun release() {
        calls += "release"
        releaseCount++
    }
}
