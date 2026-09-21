package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A libvlc stand-in for [VlcjVideoEngine]'s seam. It keeps VLC's threading shape — events arrive on
 * an "event thread", [VlcNativePlayer.submit] runs on a separate "task thread" — and records every
 * native call with the thread it was made on, so the engine's threading rules can be asserted.
 */
internal class FakeVlcRuntime : VlcRuntime {

    val eventThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, EVENT_THREAD).apply { isDaemon = true } }
    val taskThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, TASK_THREAD).apply { isDaemon = true } }

    /** Every native call on every player and on the runtime, as "name@thread", in order. */
    val log: MutableList<String> = CopyOnWriteArrayList()

    val players: MutableList<FakeVlcPlayer> = CopyOnWriteArrayList()

    /** What each new player's parser reports; `null` leaves parsing pending until [FakeVlcPlayer.fire]. */
    @Volatile var parseResult: VlcParseStatus? = VlcParseStatus.DONE

    @Volatile var parsedInfo: VlcParsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 1, videoSize = null)

    @Volatile var released: Int = 0

    val player: FakeVlcPlayer get() = players.last()

    override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
        record("newPlayer")
        return FakeVlcPlayer(this, callbacks).also { players += it }
    }

    override fun release() {
        record("runtime.release")
        released++
    }

    fun record(call: String) {
        log += "$call@${Thread.currentThread().name}"
    }

    /** Waits until every task already submitted to the task thread has run. */
    fun drainTasks() {
        taskThread.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    fun shutdown() {
        eventThread.shutdownNow()
        taskThread.shutdownNow()
    }

    companion object {
        const val EVENT_THREAD: String = "fake-vlc-events"
        const val TASK_THREAD: String = "fake-vlc-tasks"
        const val WAIT_SECONDS: Long = 5L
    }
}

internal class FakeVlcPlayer(
    private val runtime: FakeVlcRuntime,
    private val callbacks: VlcPlayerCallbacks,
) : VlcNativePlayer {

    /** Native calls on this player only, as "name" or "name(arg)", with the thread of each. */
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val callThreads: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var mrl: String? = null
    @Volatile var playhead: Long = -1L
    @Volatile private var repeat: Boolean = false

    /** When set, [stop] blocks until it opens — a network input libvlc takes its time giving up on. */
    @Volatile var stopGate: CountDownLatch? = null

    /** Called from [release], on whatever thread releases this player. */
    @Volatile var onRelease: () -> Unit = {}

    private fun call(name: String) {
        calls += name
        callThreads += Thread.currentThread().name
        runtime.record(name)
    }

    fun threadOf(name: String): String = callThreads[calls.indexOf(name)]

    /** Delivers an event the way VLC does — on the event thread — and waits for it to be handled. */
    fun fire(event: VlcPlayerCallbacks.() -> Unit) {
        runtime.eventThread.submit { callbacks.event() }.get(FakeVlcRuntime.WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Delivers one solid-colour RV32 picture, as libvlc's video-output thread would. */
    fun displayFrame(width: Int, height: Int, rgb: Int) {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (index: Int in 0 until width * height) buffer.putInt(rgb)
        fire { display(buffer, width, height) }
    }

    override fun prepare(mrl: String, options: List<String>): Boolean {
        call("prepare")
        this.mrl = mrl
        return true
    }

    override fun parse(): Boolean {
        call("parse")
        val result: VlcParseStatus? = runtime.parseResult
        if (result != null) runtime.eventThread.execute { callbacks.parsed(result) }
        return true
    }

    override fun parsedInfo(): VlcParsedInfo = runtime.parsedInfo

    override fun play() = call("play")

    override fun pause() = call("pause")

    override fun setTime(timeMs: Long) {
        call("setTime($timeMs)")
        playhead = timeMs
    }

    override fun time(): Long = playhead

    @Volatile private var rate: Float = 1f
    @Volatile private var volume: Int = -1

    override fun setRate(rate: Float) {
        call("setRate($rate)")
        this.rate = rate
    }

    override fun setVolume(percent: Int) {
        call("setVolume($percent)")
        volume = percent
    }

    override fun rate(): Float = rate

    override fun volume(): Int = volume

    override fun setRepeat(repeat: Boolean) {
        this.repeat = repeat
    }

    override fun repeat(): Boolean = repeat

    override fun submit(task: () -> Unit) {
        runtime.taskThread.execute(task)
    }

    override fun stopParsing() = call("stopParsing")

    override fun stop() {
        call("stop")
        stopGate?.await(FakeVlcRuntime.WAIT_SECONDS, TimeUnit.SECONDS)
    }

    override fun release() {
        call("release")
        onRelease()
    }
}
