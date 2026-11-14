package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter

/**
 * The world the [StrictShadowMediaRecorder] instances live in: what the engine did to them and what a
 * test scripted them to do. Reset before every test by [reset] (the Robolectric `@Resetter` below
 * calls it too, so nothing leaks between tests of one sandbox).
 */
internal object StrictRecorderWorld {

    /** Every call received, across all instances, as `instanceId:call`. */
    val calls: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Instances that were constructed and not yet released. */
    val live: AtomicInteger = AtomicInteger(0)

    /** Instances ever constructed. */
    val created: AtomicInteger = AtomicInteger(0)

    /** Every instance, oldest first. */
    val instances: CopyOnWriteArrayList<StrictShadowMediaRecorder> = CopyOnWriteArrayList()

    /** Which constructor each instance used: `"()"` or `"(Context)"`. */
    val constructors: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** Throwable raised from the named call (sticky until removed), keyed by the platform method name. */
    val failures: java.util.concurrent.ConcurrentHashMap<String, Throwable> = java.util.concurrent.ConcurrentHashMap()

    /** What `getMaxAmplitude` answers (null = the real strict behaviour). */
    @Volatile
    var amplitude: Int = 0

    fun reset() {
        calls.clear()
        live.set(0)
        created.set(0)
        instances.clear()
        constructors.clear()
        failures.clear()
        amplitude = 0
    }
}

/**
 * A `MediaRecorder` that follows the platform's documented state machine and throws what the
 * platform throws when it is called in the wrong state (`IllegalStateException`), opens its output
 * file the way the platform does (at `prepare`, `IOException` when it cannot), and counts every
 * instance that was constructed and never released. Not a copy of Robolectric's own shadow, which
 * has no state machine and no failure injection.
 *
 * Not the real encoder: a JVM host cannot run `MediaRecorder` (it needs the media server and a
 * microphone), so this is the strictest stand-in that can be built here. It writes no audio data.
 */
@Implements(MediaRecorder::class)
class StrictShadowMediaRecorder {

    enum class State { INITIAL, INITIALIZED, CONFIGURED, PREPARED, RECORDING, PAUSED, STOPPED, RELEASED }

    @RealObject
    private lateinit var real: MediaRecorder

    @Volatile
    var state: State = State.INITIAL
        private set

    val id: Int = StrictRecorderWorld.created.incrementAndGet()

    var seenOutputFormat: Int = -1
    var seenEncoder: Int = -1
    var seenSampleRate: Int = -1
    var seenChannels: Int = -1
    var seenBitRate: Int = -1
    var seenMaxFileSize: Long = -1
    var path: String? = null
    var errorListener: MediaRecorder.OnErrorListener? = null
    var infoListener: MediaRecorder.OnInfoListener? = null
    val audioCallbacks: CopyOnWriteArrayList<AudioManager.AudioRecordingCallback> = CopyOnWriteArrayList()
    private var file: RandomAccessFile? = null

    private fun log(call: String) {
        StrictRecorderWorld.calls += "$id:$call"
        StrictRecorderWorld.failures[call.substringBefore('(')]?.let { throw it }
    }

    private fun require(vararg allowed: State, call: String) {
        if (state == State.RELEASED || state !in allowed) {
            throw IllegalStateException("$call in state $state")
        }
    }

    private fun register() {
        StrictRecorderWorld.instances += this
        StrictRecorderWorld.live.incrementAndGet()
    }

    @Implementation
    protected fun __constructor__() {
        StrictRecorderWorld.constructors += "()"
        register()
    }

    @Implementation
    protected fun __constructor__(context: Context) {
        StrictRecorderWorld.constructors += "(Context)"
        register()
    }

    @Implementation
    protected fun setAudioSource(source: Int) {
        log("setAudioSource")
        require(State.INITIAL, call = "setAudioSource")
        state = State.INITIALIZED
    }

    @Implementation
    protected fun setOutputFormat(format: Int) {
        log("setOutputFormat")
        require(State.INITIALIZED, call = "setOutputFormat")
        seenOutputFormat = format
        state = State.CONFIGURED
    }

    @Implementation
    protected fun setAudioEncoder(encoder: Int) {
        log("setAudioEncoder")
        require(State.CONFIGURED, call = "setAudioEncoder")
        seenEncoder = encoder
    }

    @Implementation
    protected fun setAudioSamplingRate(rate: Int) {
        log("setAudioSamplingRate")
        require(State.CONFIGURED, call = "setAudioSamplingRate")
        if (rate <= 0) throw IllegalArgumentException("rate $rate")
        seenSampleRate = rate
    }

    @Implementation
    protected fun setAudioChannels(count: Int) {
        log("setAudioChannels")
        require(State.CONFIGURED, call = "setAudioChannels")
        seenChannels = count
    }

    @Implementation
    protected fun setAudioEncodingBitRate(rate: Int) {
        log("setAudioEncodingBitRate")
        require(State.CONFIGURED, call = "setAudioEncodingBitRate")
        if (rate <= 0) throw IllegalArgumentException("bit rate $rate")
        seenBitRate = rate
    }

    @Implementation
    protected fun setMaxFileSize(bytes: Long) {
        log("setMaxFileSize")
        require(State.CONFIGURED, call = "setMaxFileSize")
        seenMaxFileSize = bytes
    }

    @Implementation
    protected fun setOutputFile(path: String) {
        log("setOutputFile")
        require(State.CONFIGURED, call = "setOutputFile")
        this.path = path
    }

    @Implementation
    protected fun prepare() {
        log("prepare")
        require(State.CONFIGURED, call = "prepare")
        val target: String = path ?: throw IllegalStateException("prepare without an output file")
        if (seenEncoder < 0) throw IllegalStateException("prepare without an encoder")
        // The platform opens the file here, so an unwritable place surfaces as an IOException.
        file = RandomAccessFile(File(target), "rw")
        state = State.PREPARED
    }

    @Implementation
    protected fun start() {
        log("start")
        require(State.PREPARED, call = "start")
        state = State.RECORDING
    }

    @Implementation
    protected fun pause() {
        log("pause")
        require(State.RECORDING, call = "pause")
        state = State.PAUSED
    }

    @Implementation
    protected fun resume() {
        log("resume")
        require(State.PAUSED, call = "resume")
        state = State.RECORDING
    }

    @Implementation
    protected fun stop() {
        log("stop")
        require(State.RECORDING, State.PAUSED, call = "stop")
        state = State.STOPPED
        file?.close()
        file = null
    }

    @Implementation
    protected fun getMaxAmplitude(): Int {
        log("getMaxAmplitude")
        require(State.RECORDING, State.PAUSED, call = "getMaxAmplitude")
        return StrictRecorderWorld.amplitude
    }

    @Implementation
    protected fun setOnErrorListener(listener: MediaRecorder.OnErrorListener?) {
        errorListener = listener
    }

    @Implementation
    protected fun setOnInfoListener(listener: MediaRecorder.OnInfoListener?) {
        infoListener = listener
    }

    @Implementation
    protected fun registerAudioRecordingCallback(executor: Executor, callback: AudioManager.AudioRecordingCallback) {
        StrictRecorderWorld.failures["registerAudioRecordingCallback"]?.let { throw it }
        audioCallbacks += callback
    }

    @Implementation
    protected fun unregisterAudioRecordingCallback(callback: AudioManager.AudioRecordingCallback) {
        StrictRecorderWorld.failures["unregisterAudioRecordingCallback"]?.let { throw it }
        audioCallbacks -= callback
    }

    @Implementation
    protected fun getActiveRecordingConfiguration(): AudioRecordingConfiguration? {
        StrictRecorderWorld.failures["getActiveRecordingConfiguration"]?.let { throw it }
        return null
    }

    @Implementation
    protected fun release() {
        StrictRecorderWorld.calls += "$id:release"
        if (state != State.RELEASED) {
            StrictRecorderWorld.live.decrementAndGet()
            state = State.RELEASED
            runCatching { file?.close() }
            file = null
        }
        StrictRecorderWorld.failures["release"]?.let { throw it }
    }

    fun handle(): MediaRecorder = real

    fun fireError(what: Int, extra: Int) {
        errorListener?.onError(real, what, extra)
    }

    fun fireInfo(what: Int, extra: Int) {
        infoListener?.onInfo(real, what, extra)
    }

    companion object {
        @JvmStatic
        @Resetter
        fun reset() = StrictRecorderWorld.reset()
    }
}
