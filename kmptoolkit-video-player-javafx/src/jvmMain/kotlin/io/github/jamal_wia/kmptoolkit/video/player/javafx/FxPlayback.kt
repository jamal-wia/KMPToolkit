package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The playback logic over one loaded source: what to give the native player and when. The native player itself
 * (a `MediaPlayer`, the off-screen `MediaView` it paints into, and the pulse that copies that view into memory)
 * is a [NativePlayer], and the FX thread with its clock and timers is an [FxHost]; every JavaFX type the engine
 * uses lives in `JavaFxNativePlayer.kt`, so this file and [JavaFxVideoEngine] load on a classpath without OpenJFX.
 *
 * All state is confined to the FX application thread; the public methods may be called from any
 * thread and hop there themselves. Nothing here decides whether an event still matters — [Sink]
 * does that, so a disposed playback's late events are dropped in one place.
 *
 * Clock convention: every stamp and deadline is a [System.nanoTime] reading, which has no zero and no sign, so "none" is
 * a null and never 0, and two readings are only ever compared as a difference (`now - deadline < 0`), which stays right
 * when the counter wraps.
 */
@OptIn(ToolkitInternalApi::class)
internal class FxPlayback private constructor(
    private val native: NativePlayer,
    private val host: FxHost,
    private val sink: Sink,
    maxFrameRate: Int,
) {

    /** Where events go. Called on the FX thread. */
    interface Sink {
        fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long)

        /**
         * A [seekTo] has reached the FX thread — called whether or not it could still seek, so
         * every [seekTo] is answered once (unless the toolkit has exited and the call was dropped).
         */
        fun onSeekApplied()

        fun onVideoSize(size: VideoSize?)
        fun onBuffering(isBuffering: Boolean)
        fun onCompleted()
        fun onFailed(cause: Throwable)
        fun onFrame(frame: VideoFrame)
    }

    private val minFrameIntervalNanos: Long = 1_000_000_000L / maxFrameRate

    /** Completes once, with READY or the first error; later errors go to [Sink.onFailed]. */
    private val ready: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Where the next snapshot is copied. Handed over with the frame it becomes and never touched
     * again — the consumer holds the latest frame through a conflated flow and may draw it at any
     * time, so a recycled array could be overwritten mid-draw. Kept only while a snapshot is not
     * published (no picture yet).
     */
    private var scratch: IntArray? = null
    private var lastSnapshotNanos: Long? = null
    private var awaitingPicture: Boolean = true
    private var refreshUntilNanos: Long? = null
    private var looping: Boolean = false

    /**
     * [looping] as JavaFX's end-of-cycle decision read it, taken in the task where JavaFX decided (see [NativeEvents.onCycleDecision]);
     * null when no decision was seen since the last end handler. JavaFX decides loop-or-end in one FX task and posts the end handler as a
     * later one, so a [setLooping] in between must not change what that handler does with an end JavaFX has already made (a loop it
     * has already restarted is not an end, and an end is not a loop): the loop branch of the handler runs only when this and [looping] say loop.
     */
    private var loopingAtDecision: Boolean? = null
    private var atEnd: Boolean = false

    /**
     * Whether the last [start] has not been followed by [pause] or the end of the source. JavaFX's own status
     * lags a pause() (it changes when the native side answers), so a start right after a pause would still
     * read PLAYING there.
     */
    private var playIssued: Boolean = false

    /**
     * Whether a native seek has been posted since the last [start]'s play() (see [start]). A play that [start] delays
     * consumes a seek made during its wait, as a play issued at once consumes one made before it: the delayed play clears
     * the mark, or a later resume would take the seek path with no native seek behind it.
     */
    private var seekedSincePlay: Boolean = false

    /**
     * When the last [start] issued a plain play(), with no native seek before it and no rate given after it
     * ([System.nanoTime]), or null when there is no such play. A speed given within [FRESH_PLAY_NANOS] of it is given the
     * way [start] gives a resume at a speed (see [setSpeed]).
     */
    private var playIssuedAtNanos: Long? = null

    /**
     * When the last native `seek()` was posted ([System.nanoTime]), or null when none has been; the restart of a looping
     * source at a wrap counts as one (see `onEndOfMedia`). A speed given while playing
     * within [SEEK_RATE_SETTLE_NANOS] of it is not written at once (see [setSpeed]): on macOS a rate change that close
     * to a native seek can leave the native player PLAYING with its playhead standing at the seek target for good, and
     * a player in that state is never kicked again. Only [setSpeed] holds a speed back for the wrap; the speed a wrap
     * gives back follows [deferRate]'s own wait (W-WRAP is 100 ms), not this window.
     */
    private var lastNativeSeekNanos: Long? = null

    /**
     * True from a loop wrap (JavaFX's own restart seek put the native playhead at the start) until a native seek of ours or the
     * caller's is posted. While it is set, a [start] near the start does not take the seek path (a seek and a rate change on that
     * native state held the macOS playhead still in about one resume in ten, B6/W2); it resumes with a plain play on the settle path.
     */
    private var atLoopRestart: Boolean = false

    /**
     * The speed [start] has still to give after its play(): given two FX tasks later, so a speed the caller gives right
     * after [start] (the shared player gives it that way, from any thread, the FX thread included) replaces it and
     * reaches the native player once, not as a second change on top of the first. Null when nothing is pending.
     */
    private var pendingNudge: Double? = null

    /**
     * The speed a resume without a native seek has still to give (see [start]): given as one away-and-back once the
     * playhead has really moved on for [RESUME_RATE_SETTLE_MS], on a tick of the playhead (see [applyResumeRate]).
     * Null when nothing is pending. A speed that arrives meanwhile replaces it.
     *
     * One holder: at most one of [pendingNudge], [settleNudge] and [deferredRate] is set at any time. [setSpeed] replaces the one
     * that is set, in that order, and every path that sets one either clears the others or runs only when they are null, so the
     * one that is set is the newest speed asked for.
     */
    private var settleNudge: Double? = null

    /** [settleNudge] is not given before this ([System.nanoTime]), or null when it may be given at once: a seek restarts the wait (see [seekTo]). */
    private var settleNotBeforeNanos: Long? = null

    /**
     * Counts the movement of the playhead for [settleNudge]: anchored on the first tick after [settleNudge] was set, or
     * on a later tick that did not move the way the clock allows (see [MovementGauge]).
     */
    private val resumeGauge: MovementGauge = MovementGauge()

    /**
     * Counts the resumes that may delay their play() and the pauses: a play() [start] delayed to wait out the idle gap
     * (see [idleRateWriteNanos]) runs only if it is still the newest, so a [pause] or a later [start] that came while it
     * waited cancels it by bumping the counter. Every delayed play() of [start] (the settle path, the plain path and the
     * seek path) takes its generation from here.
     */
    private var playGeneration: Long = 0L

    /**
     * True from the moment [start] schedules a delayed `native.play()` (a resume inside the idle gap, see
     * [idleRateWriteNanos], or the settle path's own wait) until that play is issued or [pause] or the end of the media
     * cancels it. A second [start] in that time must not issue a play of its own: see the guard at its top.
     */
    private var delayedPlayPending: Boolean = false

    /**
     * Whether the native `play()` of the current [start] has been issued: false before [start], after [pause] or the end of
     * the media, and while the delayed play of [start] still waits. In that last state [playIssued] is already true but the
     * native player is paused by our own command, so a rate written then is a rate written while the player is not playing
     * (see [idleRateWriteNanos]), and a status that still reads PLAYING (JavaFX reports a pause late) is stale.
     */
    private val nativePlayIssued: Boolean get() = playIssued && !delayedPlayPending

    /**
     * When the rate property was last written while the native player was not playing ([System.nanoTime]), or null when
     * it has not been: the paused write of [setSpeed], the held speed [pause] writes after its pause(), the speed [pause]
     * flushes from a cancelled delayed play, the held speed the end of the source writes after its pause(), the write of
     * [giveNudge] on a player that was paused again, and the return to 1.0 of [deferRate] when JavaFX delivers a loop wrap
     * after a pause(), also while a delayed play() of a later [start] still waits. A play() issued within
     * [IDLE_RATE_GAP_NANOS] of such a write can freeze the playhead on macOS, so every [start] that resumes waits out the
     * rest of the gap first, and every delayed play() checks the gap again before it plays (see [start]). Only a write
     * that changes the value is stamped: the property forwards nothing otherwise.
     */
    private var idleRateWriteNanos: Long? = null

    /** A deferred rate is not given before this ([System.nanoTime]), or null when it may be given at once: a seek restarts the wait (see [seekTo]). */
    private var deferredNotBeforeNanos: Long? = null

    /**
     * The speed to give the native player once the playhead has really moved: after a loop wrap (the property holds 1.0
     * until then, see [deferRate]) or after a speed given right after a native seek while playing (the property keeps the
     * old speed, see [setSpeed]). Null when the property holds the speed.
     */
    private var deferredRate: Double? = null

    /** Counts the movement of the playhead for [deferredRate], the way [resumeGauge] does for [settleNudge] (see [MovementGauge]). */
    private val deferredGauge: MovementGauge = MovementGauge()

    /** Whether [deferredRate] waits for the playhead to wrap to the start of a looping source first. */
    private var deferredAfterWrap: Boolean = false

    // The ticket of the newest seekTo: taken on the caller's thread before posting, read on the FX thread
    // (D-24: a value shared between threads, in a module with a coroutines dependency).
    private val latestSeek: MutableStateFlow<Long> = MutableStateFlow(0L)
    private var buffering: Boolean = false
    private var reportedSize: VideoSize? = null
    private var failed: Boolean = false
    private var disposed: Boolean = false

    /** Whether the native pulse runs (see [NativePlayer.startPulse]). FX-thread confined, like everything here. */
    private var pulsing: Boolean = false

    init {
        native.setEvents(object : NativeEvents {
            override fun onReady() {
                reportTimes()
                reportSize()
                // The first picture is decoded around READY, not necessarily before it: the pulse keeps
                // trying until a snapshot is opaque (see awaitingPicture).
                startPulse()
                ready.complete(Unit)
            }

            override fun onPlayerError() = fail(native.playerError ?: native.mediaError)

            override fun onMediaError() = fail(native.mediaError ?: native.playerError)

            override fun onCycleDecision() {
                if (!disposed) loopingAtDecision = looping
            }

            override fun onEndOfMedia() {
                // Fired at the end of every cycle, including while cycleCount is INDEFINITE and JavaFX
                // is already starting the next one — that is not an end. A handler JavaFX queued before
                // dispose() can still run after it, and must then do nothing.
                if (disposed) return
                // A decision event that never came (a currentCount that did not change) leaves today's reading: the current setting.
                val javaFxLooped: Boolean = loopingAtDecision ?: looping
                loopingAtDecision = null
                if (looping && javaFxLooped) {
                    // JavaFX starts the next cycle with a native seek to the start time of its own, which this class does
                    // not issue: stamp it, so a speed given to a player playing at 1x right after the wrap is held back like
                    // one given after any other native seek (see [setSpeed]). The stamp lands slightly after JavaFX's seek,
                    // which errs on the safe side.
                    lastNativeSeekNanos = host.nanoTime()
                    atLoopRestart = true
                    // The native player starts the next cycle at normal speed (macOS), so a speed other than 1 is lost
                    // here unless it is given again.
                    deferRate(afterWrap = true)
                    return
                }
                atEnd = true
                // JavaFX leaves its status at PLAYING after the last cycle ends. The player reports
                // Completed, and a seek from there reports Paused — so JavaFX must be paused too, or a
                // seek would resume playback on a backend that honours the status, and the pulse
                // would keep copying frames of a picture that does not move. The end cancels a play that still waits and
                // flushes the held speeds exactly as pause() does (see [stopNativePlay]).
                stopNativePlay()
                reportTimes()
                sink.onCompleted()
            }

            override fun onStatus(status: NativeStatus) {
                setBuffering(status == NativeStatus.STALLED)
                // JavaFX reports PLAYING some time after play(): a pulse in between may already have
                // stopped, and a stall longer than the refresh window stops it too.
                if (status == NativeStatus.PLAYING) {
                    startPulse()
                }
            }

            override fun onCurrentTime() {
                applyDeferredRate()
                applyResumeRate()
                reportTimes()
                if (native.status != NativeStatus.PLAYING) refreshPicture()
            }

            override fun onBufferProgress() = reportTimes()

            override fun onDuration() = reportTimes()

            override fun onSize() = reportSize()

            override fun onPulse(nowNanos: Long) = this@FxPlayback.onPulse(nowNanos)
        })
        // Anything already failed before the handlers were installed.
        (native.playerError ?: native.mediaError)?.let { fail(it) }
    }

    fun start(): Unit = host.post {
        if (disposed) return@post
        // A start while the delayed play of an earlier start still waits is not a resume (the earlier one already issued
        // it), so it would take the plain path below and call play() at once: right next to the rate write the wait
        // stays clear of, the measured freeze, and then the delayed play again. The earlier start owns the play; a speed
        // given meanwhile is held by [setSpeed] (C3), so nothing is left for this call to do.
        if (playIssued && delayedPlayPending) return@post
        if (atEnd) {
            atEnd = false
            native.seek(native.startTimeMs)
            lastNativeSeekNanos = host.nanoTime()
            atLoopRestart = false
            seekedSincePlay = true
        }
        // Keeps the pulse alive until the status turns PLAYING (the listener restarts it then).
        refreshPicture()
        val rate: Double = deferredRate ?: native.rate
        val resuming: Boolean = !playIssued
        playIssued = true
        // C2: how much of the idle gap after a rate written while not playing is still left. A resume inside it must
        // not issue its play() yet: a play() right next to such a write is the measured freeze (see
        // [idleRateWriteNanos]). Zero or negative when there is nothing to wait for, and for a start that is not a resume.
        val idleWait: Long = if (resuming) idleGapLeftNanos() else 0L
        if (!resuming || rate == 1.0) {
            val hadSeek: Boolean = seekedSincePlay
            seekedSincePlay = false
            if (idleWait > 0L) {
                // C2: the plain resume waits out the rest of the gap, as the settle path below does, and only the newest
                // resume plays. C3: the speed that arrives while it waits is held in [settleNudge] and never written to
                // the property (a write now would be a second idle write right in front of the play); [setSpeed]
                // replaces the held value, and [applyResumeRate] gives it once the playhead has moved.
                settleNudge = rate
                // O-1: this start takes the speed asked for over as its held speed; a deferred 1.0 left beside it would be a
                // second holder that setSpeed does not update, and a later pause or start would read it (one holder, see
                // [settleNudge]).
                deferredRate = null
                settleNotBeforeNanos = null
                resumeGauge.reset()
                val generation: Long = ++playGeneration
                delayedPlayPending = true
                host.after(idleWait / 1_000_000.0 + 1.0) {
                    playWhenGapClear(generation) {
                        native.play()
                        // A seek made during the wait comes before this play, like one made before the start: no fresh-play
                        // stamp, and the play has consumed the mark.
                        playIssuedAtNanos = if (!hadSeek && !seekedSincePlay) host.nanoTime() else null
                        seekedSincePlay = false
                    }
                }
                return@post
            }
            native.play()
            playIssuedAtNanos = if (resuming && !hadSeek) host.nanoTime() else null
            return@post
        }
        deferredRate = null
        if (!seekedSincePlay && (!nearStart() || atLoopRestart)) {
            // A plain resume at a speed other than 1 (macOS: play() starts the native player at normal speed whatever
            // the rate property says, and a rate change right after a plain play() can freeze the playhead). No native
            // seek here: a seek lands on the key frame at or before the playhead, so the clip would resume from there,
            // up to a whole key-frame interval earlier. Play at the speed the player stands at, and give the speed
            // as one away-and-back once the native player has been playing for a moment (see [applyResumeRate]).
            // A player paused where its loop restarted reads near the start too, however long ago the loop wrapped, and takes
            // this path for the same reason as a playhead further on: JavaFX made a native seek of its own to the start, and a
            // second one of ours, with the pause, play and rate writes around it, is the sequence that left the macOS player
            // standing at 0 (see [atLoopRestart]); it is the state that matters, not the time since the wrap.
            settleNudge = rate
            settleNotBeforeNanos = null
            resumeGauge.reset()
            val generation: Long = ++playGeneration
            val wait: Long = idleGapLeftNanos()
            if (wait > 0L) {
                delayedPlayPending = true
                host.after(wait / 1_000_000.0 + 1.0) {
                    playWhenGapClear(generation) {
                        native.play()
                        // A seek made during the wait comes before this play: no fresh-play stamp, mark consumed.
                        playIssuedAtNanos = if (seekedSincePlay) null else host.nanoTime()
                        seekedSincePlay = false
                    }
                }
            } else {
                native.play()
                playIssuedAtNanos = host.nanoTime()
            }
            return@post
        }
        // A native seek is already posted (the caller's seek, or the restart after the end), or the playhead stands at
        // the start, where a seek to it moves nothing (the first play, a replay): a rate change right after a native
        // seek is safe and effective when it follows a play() issued after the seek, while the native player is not yet
        // playing, so the speed is given right after play() as the old nudge, away and back. That is not the case of
        // [setSpeed] on a player that is already playing when the seek is posted (see [lastNativeSeekNanos]): there the
        // same rate change can freeze the playhead. Both are true for different states: do not merge the two paths.
        if (idleWait > 0L && seekedSincePlay) {
            // C2 on the seek path: it waits whenever a seek is pending, whatever the order of that seek and the idle write
            // (a stop's seek after the pause flush and the replay's own seek wait too). That errs on the safe side of the
            // model's reading that a seek after the write clears the gap. Wait out the rest of the gap, then play and
            // give the nudge. C3: the speed is held in [pendingNudge] meanwhile, which [setSpeed] replaces and [pause]
            // flushes (C4); the nudge keeps its shape of play first, two FX tasks later the away-and-back.
            seekedSincePlay = false
            playIssuedAtNanos = null
            pendingNudge = rate
            val generation: Long = ++playGeneration
            delayedPlayPending = true
            host.after(idleWait / 1_000_000.0 + 1.0) {
                playWhenGapClear(generation) {
                    native.play()
                    // A seek made during the wait comes before this play: the play has consumed the mark.
                    seekedSincePlay = false
                    host.postLater { host.postLater { giveNudge() } }
                }
            }
            return@post
        }
        if (!seekedSincePlay) {
            native.seek(native.currentTimeMs)
            lastNativeSeekNanos = host.nanoTime()
            atLoopRestart = false
        }
        native.play()
        seekedSincePlay = false
        playIssuedAtNanos = null
        pendingNudge = rate
        // Two tasks later, not one: called on the FX thread, the shared player's setSpeed right after start() queues
        // behind a nudge posted one task later (post() runs inline only when nothing is queued), found the nudge already
        // given and wrote the speed as a third rate change right after play(), which can freeze the playhead on macOS.
        // One more hop puts that setSpeed in front of the nudge, where it replaces it.
        host.postLater { host.postLater { giveNudge() } }
    }

    /**
     * Gives the native player the speed [start] left pending, as the old nudge: away and back. While the delayed play of a
     * start still waits it returns without consuming the value. A value left this way is always consumed later: by the nudge
     * of the start that owns it, after its play, or by the flush of the [pause] or the end of the media that cancels that
     * start (or, at a loop wrap, taken over by [deferRate]).
     */
    private fun giveNudge() {
        if (disposed) return
        // S20: a start whose play() still waits owns [pendingNudge] and gives it itself, after its own play(). A nudge queued
        // by an earlier start (two FX tasks away from a start, a pause() and a delayed start may run in between) would
        // consume it now and write 1.0 and the speed on the paused decoder.
        if (delayedPlayPending) return
        val rate: Double = pendingNudge ?: return
        pendingNudge = null
        if (!playIssued) {
            // Paused again meanwhile: the property only keeps the speed for the next start.
            // C1: that is a write on a player that is not playing, so it is stamped like any other: without the stamp
            // the next start would play right next to it, inside the idle gap, and nothing would wait.
            if (native.rate != rate) idleRateWriteNanos = host.nanoTime()
            native.rate = rate
            return
        }
        native.rate = 1.0
        native.rate = rate
    }

    fun pause(): Unit = host.post {
        if (disposed) return@post
        stopNativePlay()
    }

    /**
     * What [pause] and the end of a non-looping clip have in common: the native player stops, the play a [start] still
     * waits to issue is cancelled for good (the generation moves on, so its callback does nothing), and every speed held for
     * it is written on the paused player, stamped, and cleared. Called on the FX thread, on a player that is not disposed.
     */
    private fun stopNativePlay() {
        playIssued = false
        playGeneration++
        delayedPlayPending = false
        // A speed that waits ([deferredRate]) survives the pause (the next start() takes any speed but 1), but the
        // movement counted for it does not: the count begins again from the first PLAYING reading after the next start(),
        // or the movement counted before the pause would complete the wait on that reading and the speed would be written
        // right after the play (W-PLAY).
        deferredGauge.reset()
        native.pause()
        // A1: the held speed is written after the pause, never before it. OpenJFX forwards a rate change and a pause()
        // synchronously and in order, so a write ahead of the pause() lands on a decoder that is still running (the
        // play of W-PLAY or the seek of W-SEEK-RUN may be only milliseconds old), the shape no probe has measured.
        // After the pause() it is the write on a player that is not playing that this method always stamped it as,
        // and the idle gap of the next start (C2) keeps that write away from the next play().
        settleNudge?.let {
            if (native.rate != it) idleRateWriteNanos = host.nanoTime()
            native.rate = it
        }
        settleNudge = null
        // C4: a speed held for a delayed seek-path play that this pause (or the end of the clip) cancelled would stay in [pendingNudge] for
        // good: the next speeds would be swallowed into it (see [setSpeed]) and the next start would never give them.
        // Flush it the way [giveNudge] does on a player that is paused again, stamped, and clear it.
        pendingNudge?.let {
            if (native.rate != it) idleRateWriteNanos = host.nanoTime()
            native.rate = it
        }
        pendingNudge = null
    }

    fun seekTo(positionMs: Long) {
        val ticket: Long = latestSeek.updateAndGet { it + 1 }
        host.post {
            // A newer seek is queued behind this one and overrides its position: skip the native work.
            // The newer ticket is taken before its block is posted, and host.post runs a block inline
            // only when nothing is queued, so a block that sees a newer ticket always has that newer
            // block still behind it. onSeekApplied still answers every seek, a skipped one included.
            if (!disposed && ticket == latestSeek.value) {
                atEnd = false
                native.seek(positionMs.toDouble())
                lastNativeSeekNanos = host.nanoTime()
                atLoopRestart = false
                seekedSincePlay = true
                if (settleNudge != null) {
                    // Same as below: the playhead jumped, wait again from the new place.
                    resumeGauge.reset()
                    settleNotBeforeNanos = host.nanoTime() + SEEK_RATE_SETTLE_NANOS
                }
                if (deferredRate != null) {
                    // The playhead jumped without the native player having settled, and a rate change within about
                    // 100 ms of a native seek can make the playhead stand still: wait again, from the new place.
                    deferredGauge.reset()
                    deferredAfterWrap = false
                    deferredNotBeforeNanos = host.nanoTime() + SEEK_RATE_SETTLE_NANOS
                }
                refreshPicture()
            }
            sink.onSeekApplied()
        }
    }

    fun setSpeed(speed: Float): Unit = host.post {
        if (disposed) return@post
        val rate: Double = speed.toDouble()
        val seekAt: Long? = lastNativeSeekNanos
        val playAt: Long? = playIssuedAtNanos
        when {
            pendingNudge != null -> pendingNudge = rate
            settleNudge != null -> settleNudge = rate
            deferredRate != null -> deferredRate = rate
            rate != native.rate && playIssued && seekAt != null &&
                host.nanoTime() - seekAt < SEEK_RATE_SETTLE_NANOS -> {
                // A native seek was posted a moment ago on a player that is playing (replay() or seekTo() while
                // Playing, or a stop() followed by a play()): a rate written now can leave the native player PLAYING
                // with the playhead standing at the seek target for good (macOS, AVFoundation). Keep the property as
                // it is and hand the speed to the deferred-rate machinery: it is given once the player is PLAYING,
                // the settle time since the seek has passed and the playhead has moved on (see [applyDeferredRate]).
                // For those few hundred milliseconds the native player keeps its old speed. This branch is ahead of
                // the FRESH one on purpose: no rate is written within the window of a native seek while playing, also
                // when the seek follows a plain play() by less than FRESH_PLAY_NANOS.
                deferredRate = rate
                deferredGauge.reset()
                deferredAfterWrap = false
                deferredNotBeforeNanos = seekAt + SEEK_RATE_SETTLE_NANOS
            }
            rate != native.rate && rate != 1.0 && playIssued && playAt != null &&
                host.nanoTime() - playAt < FRESH_PLAY_NANOS -> {
                // A play() has just been issued (the shared player gives the speed right after start(), so a
                // speed changed while paused arrives here): a rate change right after a plain play() can freeze
                // the playhead on macOS. Give it the way start() gives a resume at a speed, once the native
                // player has been playing for a moment.
                playIssuedAtNanos = null
                // The seek, play and rate change at once is [start]'s seeked path: safe while the native player has not
                // started playing yet. Once its status is PLAYING (within the FRESH period the decoder often is) a seek
                // followed by a rate change is the W-SEEK-RUN case, which can freeze the playhead: [start]'s comment says
                // not to merge the two paths. A started decoder takes the settle nudge below, like a speed off the start.
                // A stale PLAYING read only sends the call down the settle path, which is safe in every state, and so does a
                // player paused where its loop restarted (see [atLoopRestart]): no seek of ours on that state.
                if (nearStart() && !atLoopRestart && native.status != NativeStatus.PLAYING) {
                    if (!seekedSincePlay) {
                        native.seek(native.currentTimeMs)
                        lastNativeSeekNanos = host.nanoTime()
                        atLoopRestart = false
                    }
                    native.play()
                    seekedSincePlay = false
                    native.rate = 1.0
                    native.rate = rate
                } else {
                    settleNudge = rate
                    settleNotBeforeNanos = null
                    resumeGauge.reset()
                }
            }
            else -> {
                if (!playIssued && native.rate != rate) idleRateWriteNanos = host.nanoTime()
                native.rate = rate
            }
        }
    }

    fun setVolume(volume: Float): Unit = host.post {
        if (!disposed) native.volume = volume.toDouble()
    }

    fun setLooping(looping: Boolean): Unit = host.post {
        if (disposed) return@post
        this.looping = looping
        native.cycleCount = if (looping) CYCLE_INDEFINITE else 1
    }

    /** What JavaFX currently holds, read on the FX thread — for tests; settings have no getter. */
    class Inspection(
        val volume: Double,
        val rate: Double,
        val cycleCount: Int,
        val status: String,
        val pulsing: Boolean,
    )

    suspend fun inspect(): Inspection = host.call {
        Inspection(native.volume, native.rate, native.cycleCount, native.status.toString(), pulsing)
    }

    /** Stops the pulse and frees the native player. Idempotent; any thread. */
    fun dispose(): Unit = host.post {
        if (disposed) return@post
        disposed = true
        stopPulse()
        ready.cancel()
        native.detachView()
        // OpenJFX race: MediaPlayer.dispose() sets DISPOSED, but a status update its native side had
        // already queued (onPlaying / onPause) can run afterwards and overwrite it, after which the
        // guards in seek() and pause() pass on a player whose native half is gone. A queued
        // end-of-media then loops (seek -> NullPointerException on the FX thread) or reaches our
        // handler. With one cycle and no handler an already queued end takes the plain non-looping
        // branch, which never seeks. Plain property writes on a player that is not yet disposed. Do
        // not "simplify" this away.
        native.clearEndOfMedia()
        native.cycleCount = 1
        native.dispose()
        scratch = null
    }

    /**
     * Gives the speed in force to the native player again at the start of a looping source's next cycle, which
     * begins at normal speed on macOS whatever the rate property says. The property only forwards a change, so
     * it is put to 1.0 here and the speed is given back as one change by [applyDeferredRate], once the playhead
     * has wrapped and moved a moment (a change made within the first ~100 ms of playback can make the playhead
     * stand still). A resume from a pause is not handled here: see [start].
     */
    private fun deferRate(afterWrap: Boolean) {
        // The newest held speed, in the order [setSpeed] fills the holders: pendingNudge first, then settleNudge, then
        // deferredRate. With one holder (see [settleNudge]) at most one is set, so the order only decides which one that is; it
        // is kept in [setSpeed]'s order so that a second holder, if one ever appeared, would not give an older speed over a
        // newer one. The others are cleared, or the speed they hold would be given again over it.
        val rate: Double = pendingNudge ?: settleNudge ?: deferredRate ?: native.rate
        pendingNudge = null
        settleNudge = null
        deferredRate = if (rate == 1.0) null else rate
        if (rate != 1.0) {
            deferredGauge.reset()
            deferredAfterWrap = afterWrap
            deferredNotBeforeNanos = null
        }
        // JavaFX can deliver the end of a cycle after a pause(), or after a later start() whose play() is still delayed (S20):
        // then 1.0 is written on a player that is not playing, which is stamped like any other such write, so the next play()
        // (or the delayed one, which checks the gap again) waits out the idle gap (see [idleRateWriteNanos]). The property is
        // put to 1.0 whenever it holds another value, also for a held speed of 1: nothing else would take the old speed out.
        if (native.rate != 1.0) {
            if (!nativePlayIssued) idleRateWriteNanos = host.nanoTime()
            native.rate = 1.0
        }
    }

    /**
     * Called on every tick of the playhead: gives the native player the deferred speed once it is safe. A speed equal to
     * the one the property holds (and not 1.0) is given as one away-and-back, like [applyResumeRate] gives its speed: a plain
     * play() on macOS starts at normal speed whatever the property holds, and a plain write of the property's own value would
     * forward nothing and leave the decoder there.
     */
    private fun applyDeferredRate() {
        // A tick JavaFX queued before dispose() can still arrive after it, with a late status that reads PLAYING again.
        if (disposed) return
        val rate: Double = deferredRate ?: return
        // S19: JavaFX reports the status of a pause late, and a reading it published before can still tick: after pause()
        // (or the end of a clip) the status may read PLAYING for a while. A write now would be a rate written while not
        // playing that nothing stamps, and a play() right after it would start inside the idle gap. The speed stays
        // deferred instead: a start() that resumes at a speed other than 1 takes it from [deferredRate] and gives it on its
        // own path; a speed of 1 stays here and is given once the playhead has moved after the play.
        // S20: the same holds while the delayed play() of a later start() still waits: the native player is paused by our own
        // command then, whatever the status says.
        if (native.status != NativeStatus.PLAYING || !nativePlayIssued) return
        if (isBefore(deferredNotBeforeNanos)) return
        val position: Double = native.currentTimeMs
        if (position.isNaN()) return
        if (deferredAfterWrap) {
            if (position > native.durationMs / 2.0) return
            deferredAfterWrap = false
        }
        // A seek through [seekTo] resets the gauge, so a step back here is a stale reading, or a native seek this class
        // did not count (the wrap of a looping source before its end handler runs): either way it is not movement.
        if (!deferredGauge.moved(position, host.nanoTime(), native.rate, LOOP_RATE_SETTLE_MS)) return
        deferredRate = null
        if (native.rate == rate && rate != 1.0) {
            // The decoder can run at a speed the property does not say: a plain play() on macOS starts at normal speed
            // whatever the property holds (the start() plain branch with a deferred 1.0), and a held speed equal to the
            // property would forward nothing and leave it there. Away and back, as applyResumeRate gives its speed.
            native.rate = 1.0
        }
        native.rate = rate
    }

    /**
     * Called on every tick of the playhead: gives the native player the speed of a resume without a native seek, as one
     * away-and-back, once the playhead has moved [RESUME_RATE_SETTLE_MS] at normal speed (a rate change within about
     * 100 ms of a plain play() can freeze the playhead on macOS; a fixed delay is a race with the native start-up).
     * The movement is counted by [resumeGauge], only as far as the clock allows (see [MovementGauge]).
     */
    private fun applyResumeRate() {
        // See applyDeferredRate: no rate is written on a disposed player.
        if (disposed) return
        val rate: Double = settleNudge ?: return
        if (native.status != NativeStatus.PLAYING || !nativePlayIssued) return
        if (isBefore(settleNotBeforeNanos)) return
        val position: Double = native.currentTimeMs
        if (position.isNaN()) return
        if (!resumeGauge.moved(position, host.nanoTime(), native.rate, RESUME_RATE_SETTLE_MS)) return
        settleNudge = null
        native.rate = 1.0
        native.rate = rate
    }

    /**
     * Counts how far the playhead has moved since an anchor, only as far as the clock allows. One gauge serves each of the
     * two paths that give a speed once the playhead has really moved: [applyResumeRate] (a resume without a native seek)
     * and [applyDeferredRate] (a loop wrap, a speed given right after a native seek while playing).
     *
     * The first time JavaFX reports after a resume can be stale (the value from before the pause, measured at 421 to 538 ms
     * behind the decoder), followed within 1 to 22 ms by a fresh one; taken as two ticks of one playhead the pair
     * is a jump of hundreds of milliseconds that happened in almost no time, which would give the speed about 0.17 s after
     * the play(), inside the native start-up that the wait exists to avoid. A reading from before a seek, landing after it,
     * is stale too: behind the playhead after a seek forward (the same shape), ahead of it after a seek back (the fresh
     * reading then steps back). Until the speed is given the decoder runs no faster than `max(1.0, rateBound)` times the
     * wall clock: after a resume, normal speed on macOS, where play() ignores the rate property, and at most the
     * property's rate elsewhere; after a loop wrap or a seek while playing, the rate the property holds. A tick that reports
     * the playhead behind its anchor, or ahead of the anchor by more than the wall time since the anchor times
     * `max(1.0, rate)` plus [RATE_SETTLE_CLOCK_SLACK_MS], is not movement of the playhead: it becomes the new anchor
     * (position and time) and the wait starts again from it. The factor keeps a decoder that really runs at a held speed
     * from being re-anchored for good. Re-anchoring on true playback is rare and finite (it takes a reading latency that
     * falls by more than the slack divided by the rate between two ticks, and the latency is bounded), so the wait
     * ends once the stale readings are past.
     */
    private class MovementGauge {
        private var fromPosition: Double = Double.NaN

        /** When [fromPosition] was taken ([System.nanoTime] of the host); meaningful only while [fromPosition] is not NaN. */
        private var fromNanos: Long = 0L

        /** Forgets the anchor: the next tick becomes the anchor. */
        fun reset() {
            fromPosition = Double.NaN
            fromNanos = 0L
        }

        /**
         * Takes one tick: [position] at [nowNanos], with the decoder running at no more than `max(1.0, rateBound)` times
         * the wall clock. The first tick, and a tick that moved the wrong way for the clock, becomes the anchor and
         * answers false; otherwise true when the playhead has moved at least [needMs] since the anchor.
         */
        fun moved(position: Double, nowNanos: Long, rateBound: Double, needMs: Double): Boolean {
            if (fromPosition.isNaN()) {
                anchor(position, nowNanos)
                return false
            }
            val movedMs: Double = position - fromPosition
            val elapsedMs: Double = (nowNanos - fromNanos) / 1_000_000.0
            if (movedMs < 0.0 || movedMs > elapsedMs * max(1.0, rateBound) + RATE_SETTLE_CLOCK_SLACK_MS) {
                anchor(position, nowNanos)
                return false
            }
            return movedMs >= needMs
        }

        private fun anchor(position: Double, nowNanos: Long) {
            fromPosition = position
            fromNanos = nowNanos
        }
    }

    /** How much of the idle gap after the last rate write while not playing is left, in nanoseconds; zero or less when none is. */
    private fun idleGapLeftNanos(): Long {
        val writeAt: Long = idleRateWriteNanos ?: return 0L
        return IDLE_RATE_GAP_NANOS - (host.nanoTime() - writeAt)
    }

    /**
     * Runs the delayed [play] of a [start] once the idle gap is clear. Called when the wait [start] scheduled ends, with the
     * generation of that [start]: nothing happens for a play that a [pause] or the end of the media has cancelled (both bump
     * the generation, see [stopNativePlay]) or that [dispose] has dropped; a later [start] does not cancel it, it returns at
     * its guard. A rate written while the native player was not playing during the wait (S20:
     * the return to 1.0 of a loop wrap JavaFX delivers after a [pause], see [deferRate]) has stamped the gap again, so the
     * wait is repeated for the rest of it, with the same generation and with [delayedPlayPending] still set. Every
     * repetition waits out the rest of the newest gap; only a handler JavaFX queued before the pause can stamp during the
     * wait, and the decoder is paused, so there are finitely many (in practice one: the wrap leaves the property at 1.0, and
     * nothing else writes during the wait). The play runs exactly once.
     */
    private fun playWhenGapClear(generation: Long, play: () -> Unit) {
        if (disposed || !playIssued || generation != playGeneration) return
        val left: Long = idleGapLeftNanos()
        if (left > 0L) {
            host.after(left / 1_000_000.0 + 1.0) { playWhenGapClear(generation, play) }
            return
        }
        delayedPlayPending = false
        play()
    }

    /** Whether [deadline] is still ahead: false for null, "no deadline". Compares as a difference, which survives a wrap. */
    private fun isBefore(deadline: Long?): Boolean = deadline != null && host.nanoTime() - deadline < 0L

    /**
     * Whether the playhead stands within [NEAR_START_MS] of the start, where a native seek to it moves nothing visible
     * (the first key frame is at 0): there a resume may seek to where it stands, and does, for the freeze it prevents.
     */
    private fun nearStart(): Boolean {
        val position: Double = native.currentTimeMs
        return position.isNaN() || position <= NEAR_START_MS
    }

    /** The first error only: JavaFX reports one failure through both the player and the media. */
    private fun fail(cause: Throwable?) {
        if (disposed || failed) return
        failed = true
        val error: Throwable = cause
            ?: IllegalStateException("JavaFX Media reported an error without a cause")
        if (!ready.completeExceptionally(error)) sink.onFailed(error)
    }

    private fun setBuffering(isBuffering: Boolean) {
        if (buffering == isBuffering) return
        buffering = isBuffering
        sink.onBuffering(isBuffering)
    }

    private fun reportTimes() {
        if (disposed) return
        val duration: Long = native.durationMs.toMillisOrZero()
        sink.onTimes(
            durationMs = duration,
            positionMs = native.currentTimeMs.toMillisOrZero(),
            bufferedMs = native.bufferProgressMs.toMillisOrZero(),
        )
    }

    private fun reportSize() {
        if (disposed) return
        val size: VideoSize? = if (native.width > 0 && native.height > 0) {
            VideoSize(native.width, native.height)
        } else {
            null
        }
        if (size == reportedSize) return
        reportedSize = size
        sink.onVideoSize(size)
        // A picture that appears later (the size was 0×0 until now) needs the pulse again.
        if (size != null) startPulse()
    }

    /** Keeps copying for a while although not playing: the picture changes after a seek. */
    private fun refreshPicture() {
        refreshUntilNanos = host.nanoTime() + REFRESH_WINDOW_NANOS
        startPulse()
    }

    private fun startPulse() {
        if (disposed || pulsing) return
        pulsing = true
        native.startPulse()
    }

    private fun stopPulse() {
        if (!pulsing) return
        pulsing = false
        native.stopPulse()
    }

    private fun onPulse(now: Long) {
        if (disposed) return
        if (native.width <= 0 || native.height <= 0) {
            // Audio only: there is no picture to copy, now or until reportSize sees one.
            stopPulse()
            return
        }
        val playing: Boolean = native.status == NativeStatus.PLAYING && !atEnd
        val refreshUntil: Long? = refreshUntilNanos
        if (!playing && !awaitingPicture && (refreshUntil == null || now - refreshUntil > 0L)) {
            // Nothing left to copy; start(), seekTo() and a changed picture start it again.
            stopPulse()
            return
        }
        // Pulses arrive at the display rate with jitter; without the slack a 30 fps cap on a 60 Hz
        // pulse regularly waits a third pulse and delivers ~24 fps.
        val lastSnapshot: Long? = lastSnapshotNanos
        if (lastSnapshot != null && now - lastSnapshot < minFrameIntervalNanos - PULSE_SLACK_NANOS) return
        lastSnapshotNanos = now
        snapshot()
    }

    private fun snapshot() {
        val width: Int = native.width
        val height: Int = native.height
        if (width <= 0 || height <= 0) return
        val pixels: IntArray = scratch?.takeIf { it.size == width * height } ?: IntArray(width * height)
        native.snapshotInto(pixels, width, height)
        if (pixels[(height / 2) * width + width / 2] ushr 24 == 0) {
            // The view has no decoded picture yet: nothing to show, and nothing was published, so
            // the array can be written again next time.
            scratch = pixels
            return
        }
        awaitingPicture = false
        scratch = null
        sink.onFrame(VideoFrame(width, height, pixels))
    }

    companion object {

        /** How far the playhead must move, at normal speed, before a rate deferred at a loop wrap is given (see [deferRate]). */
        private const val LOOP_RATE_SETTLE_MS: Double = 200.0

        /**
         * How long a plain play() keeps away from a rate written while the player was not playing (see [start]): a play()
         * issued within about this time of such a write can freeze the playhead on macOS (found at a speed of 8).
         */
        private const val IDLE_RATE_GAP_NANOS: Long = 200_000_000L

        /** How long after a play() a speed is still given the way a resume gives it (see [setSpeed]). */
        private const val FRESH_PLAY_NANOS: Long = 600_000_000L

        /** Within this of the start a native seek to the playhead moves it by no more than the documented quantisation (see [nearStart]). */
        private const val NEAR_START_MS: Double = 100.0

        /** How far the playhead must move, at normal speed, before the speed of a resume without a seek is given (see [applyResumeRate]). */
        private const val RESUME_RATE_SETTLE_MS: Double = 200.0

        /**
         * How far a tick may run ahead of the clock before it is taken as a stale-to-fresh jump of the reported time
         * and not as playback (see [MovementGauge]): above the 17 ms jitter of an FX pulse, far below the stale jumps of 421 to 538 ms
         * measured after a resume.
         */
        private const val RATE_SETTLE_CLOCK_SLACK_MS: Double = 50.0

        /** How long after a native seek a deferred rate keeps waiting, and a speed given while playing is held back (see [seekTo], [setSpeed]). */
        private const val SEEK_RATE_SETTLE_NANOS: Long = 300_000_000L

        /** How long the pulse keeps copying after the picture changed while not playing (a seek). */
        private const val REFRESH_WINDOW_NANOS: Long = 500_000_000L

        /** A quarter of a 60 Hz pulse. */
        private const val PULSE_SLACK_NANOS: Long = 4_000_000L

        /**
         * Opens [uri] and suspends until JavaFX reports it ready. `backend.probe(uri)` (`Media(uri)` for
         * JavaFX) probes the source and may block on the network, so it runs on the IO dispatcher;
         * everything else runs on the FX thread. On failure or cancellation the half-built playback is
         * disposed before this throws.
         *
         * [backend]: where the player is built; the engine passes its own.
         *
         * [onCreated] runs on the FX thread as soon as the player exists, before it is ready, so the
         * caller can route settings to it from then on.
         */
        suspend fun open(
            uri: String,
            sink: Sink,
            maxFrameRate: Int,
            readyTimeoutMs: Long,
            backend: NativeBackend,
            onCreated: (FxPlayback) -> Unit,
        ): FxPlayback {
            val source: NativeSource = withContext(Dispatchers.IO) { runInterruptible { backend.probe(uri) } }
            // An atomic: the FX block may still run after the wait timed out or was cancelled,
            // concurrently with the read below (D-21).
            val created: AtomicReference<FxPlayback?> = AtomicReference(null)
            try {
                // JavaFX can stay UNKNOWN forever — on macOS a file it cannot parse reports neither
                // READY nor an error — so the wait is bounded. So is building the player: once the
                // toolkit has exited, runLater drops the block silently and the call would never
                // resume. A block that does run late finds its session closed and disposes itself
                // (see JavaFxVideoEngine.Session.attach). An exit seen before this point fails the load
                // at once (ToolkitJavaFxRuntime re-checks it on every call); only an exit that races
                // the load itself, between that probe and this call, waits out the timeout.
                withTimeoutOrNull(readyTimeoutMs) {
                    coroutineScope {
                        // The toolkit can end at any moment, by implicit exit as well as
                        // Platform.exit(); the wait then ends at once instead of at the timeout.
                        // Silence is not taken for an exit while the FX thread runs this load's own
                        // construction (see FxThread.hasExited).
                        val exitWatch: Job = launch {
                            while (true) {
                                delay(EXIT_POLL_MS)
                                if (backend.host.hasExitedNow()) {
                                    throw JavaFxVideoPlayerException.RuntimeUnavailable(
                                        IllegalStateException("The JavaFX toolkit has exited"),
                                    )
                                }
                            }
                        }
                        val playback: FxPlayback = backend.host.call {
                            FxPlayback(backend.create(source), backend.host, sink, maxFrameRate).also {
                                created.set(it)
                                onCreated(it)
                            }
                        }
                        playback.ready.await()
                        exitWatch.cancel()
                        playback
                    }
                } ?: throw if (created.get() == null) {
                    JavaFxVideoPlayerException.RuntimeUnavailable(
                        IllegalStateException(
                            "The JavaFX application thread ran no work within $readyTimeoutMs ms; " +
                                "the toolkit has probably exited",
                        ),
                    )
                } else {
                    JavaFxVideoPlayerException.LoadTimedOut(readyTimeoutMs)
                }
                return checkNotNull(created.get())
            } catch (e: Throwable) {
                // Cancellation included: nothing playable is left behind either way.
                created.get()?.dispose()
                throw e
            }
        }

        private const val EXIT_POLL_MS: Long = 100L

        /** `MediaPlayer.INDEFINITE`, the value of [NativePlayer.cycleCount] that loops for ever. */
        private const val CYCLE_INDEFINITE: Int = -1

        private fun Double.toMillisOrZero(): Long {
            if (isNaN() || isInfinite()) return 0L
            return if (this < 0.0) 0L else toLong()
        }
    }
}
