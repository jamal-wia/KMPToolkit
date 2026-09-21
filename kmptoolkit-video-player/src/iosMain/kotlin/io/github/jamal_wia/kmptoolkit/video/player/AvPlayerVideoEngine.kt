package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.cancelLoading
import platform.AVFoundation.cancelPendingSeeks
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.rate
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [VideoPlaybackEngine] backed by `AVFoundation.AVPlayer`.
 *
 * **One `AVPlayer` for the engine's whole life.** Each [load] swaps a new `AVPlayerItem` into the
 * same player, and [release] only empties it. That is what lets `kmptoolkit-video-player-compose`
 * attach an `AVPlayerLayer` to [player] once and keep it attached across sources.
 *
 * **Threading.** Every AVFoundation call happens on the main thread: [load] hops there, and the
 * transport calls run inline when already on it (the normal case — a screen drives its player) and
 * are dispatched to it otherwise. The polled getters are answered from atomics a main-thread monitor
 * refreshes every [MONITOR_INTERVAL_MS], so the player's polling coroutine may run on any thread.
 *
 * **Observation by polling, not KVO** — for the reason `kmptoolkit-audio-player` gives: key-value
 * observing has no Kotlin/Native binding that is safe against observing a deallocated object. The
 * monitor reads `status`, `timeControlStatus`, `presentationSize` and `loadedTimeRanges` on each
 * tick and reports only changes. End of playback and mid-playback failure come from
 * `NSNotificationCenter`, delivered on the main queue.
 *
 * **Never calling the listener after [release].** Each [load] creates a [Session]; [release] swaps
 * it out atomically before anything else, and every callback — monitor tick or notification —
 * checks it is still the current session on the main thread before calling the listener.
 *
 * @param assetBundle bundle searched for [VideoSource.Asset].
 * @param assetSubdirectories bundle subdirectories searched, in order, after the bundle root.
 * @param managesAudioSession whether to switch the shared `AVAudioSession` to the playback category.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class AvPlayerVideoEngine(
    private val assetBundle: NSBundle,
    private val assetSubdirectories: List<String>,
    private val managesAudioSession: Boolean,
) : VideoPlaybackEngine {

    /** One loaded source: its AVFoundation objects, observers, and the values the getters return. */
    private class Session(val asset: AVURLAsset, val item: AVPlayerItem) {
        val durationMs: AtomicLong = AtomicLong(0L)
        val positionMs: AtomicLong = AtomicLong(0L)
        val bufferedPositionMs: AtomicLong = AtomicLong(0L)

        // Main thread only.
        val observers: MutableList<NSObjectProtocol> = mutableListOf()
        var monitor: Job? = null
        var isBuffering: Boolean = false
        var videoSize: VideoSize? = null
        var hasFailed: Boolean = false
    }

    private val session: AtomicReference<Session?> = AtomicReference(null)

    /** Hosts the per-session monitor; its jobs are cancelled per session, never the scope. */
    private val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

    /** The rate [start] plays at. Remembered because a paused AVPlayer cannot be told it yet. */
    @Volatile
    private var speed: Float = NORMAL_SPEED

    @Volatile
    private var volume: Float = FULL_VOLUME

    @Volatile
    private var looping: Boolean = false

    /** Main thread only; created on first use. */
    private var avPlayer: AVPlayer? = null

    /**
     * The engine's `AVPlayer`, created on first access and kept until the engine is garbage — the
     * same instance before, between and after sources. Main thread only.
     */
    internal val player: AVPlayer
        get() {
            check(NSThread.isMainThread) { "AvPlayerVideoEngine.player must be used on the main thread" }
            return avPlayer ?: AVPlayer().also { created: AVPlayer ->
                created.volume = volume
                avPlayer = created
            }
        }

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: VideoSource) {
        release()

        val url: NSURL = resolveUrl(source)
        val options: Map<Any?, Any?>? = urlAssetOptions(source)

        withContext(Dispatchers.Main) {
            val asset = AVURLAsset(uRL = url, options = options)
            val item = AVPlayerItem(asset = asset)
            val loading = Session(asset, item)
            session.store(loading)
            try {
                if (managesAudioSession) activateAudioSession()
                observeNotifications(loading)
                val current: AVPlayer = player
                current.volume = volume
                current.replaceCurrentItemWithPlayerItem(item)
                awaitReadyToPlay(loading)
                refresh(loading)
                // The first refresh reports the picture size; a listener may release in response.
                check(session.load() === loading) { "The engine was released while loading" }
                loading.monitor = mainScope.launch {
                    while (isActive && session.load() === loading) {
                        delay(MONITOR_INTERVAL_MS)
                        refresh(loading)
                    }
                }
            } catch (failure: Throwable) {
                // Cancelled, failed, or released under our feet: leave nothing playable behind.
                // compareAndSet so a concurrent release() that already took the session tears it
                // down exactly once.
                if (session.compareAndSet(loading, null)) tearDown(loading)
                throw failure
            }
        }
    }

    override fun start() {
        onMain {
            val current: Session = session.load() ?: return@onMain
            val avPlayer: AVPlayer = player
            avPlayer.play()
            // Right after play() the player is usually still waiting to reach its rate, so the rate
            // is assigned here too; assigning it only "while playing" would drop it.
            avPlayer.rate = speed
            refresh(current)
        }
    }

    override fun pause() {
        onMain {
            val current: Session = session.load() ?: return@onMain
            player.pause()
            refresh(current)
        }
    }

    override fun seekTo(positionMs: Long) {
        // Answer the polled getter with the target at once: the seek itself completes later.
        session.load()?.positionMs?.store(positionMs)
        onMain {
            if (session.load() == null) return@onMain
            // Zero tolerance: a seek bar and a "watched 95%" check both want the frame asked for,
            // not the nearest keyframe seconds away.
            player.seekToTime(
                time = CMTimeMakeWithSeconds(positionMs / MILLIS_PER_SECOND, CM_TIME_TIMESCALE),
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            )
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        onMain {
            val avPlayer: AVPlayer = this.avPlayer ?: return@onMain
            // A non-zero rate on a paused AVPlayer resumes it, so only a moving player (non-zero
            // rate, buffering or not) gets the new rate now; a paused one picks it up in start().
            if (avPlayer.rate != 0f) avPlayer.rate = speed
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        onMain { avPlayer?.volume = volume }
    }

    override fun setLooping(looping: Boolean) {
        this.looping = looping
    }

    override fun durationMs(): Long = session.load()?.durationMs?.load() ?: 0L

    override fun positionMs(): Long = session.load()?.positionMs?.load() ?: 0L

    override fun bufferedPositionMs(): Long = session.load()?.bufferedPositionMs?.load() ?: 0L

    override fun release() {
        // The swap comes first and is atomic: from here on no callback of that session passes its
        // "still current" check, whichever thread release() was called from.
        val released: Session = session.exchange(null) ?: return
        onMain { tearDown(released) }
    }

    /**
     * Polls `AVPlayerItem.status` until the item is playable or has failed. Main thread. Naturally
     * cancellable, which is what makes [load]'s cancellation contract hold.
     */
    private suspend fun awaitReadyToPlay(loading: Session) {
        while (true) {
            check(session.load() === loading) { "The engine was released while loading" }
            when (loading.item.status) {
                AVPlayerItemStatusReadyToPlay -> return
                AVPlayerItemStatusFailed -> throw itemFailure(loading.item.error)
                else -> delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    /** Main thread: re-reads the item, updates the cached values, reports what changed. */
    private fun refresh(current: Session) {
        if (session.load() !== current) return
        val item: AVPlayerItem = current.item

        val positionMs: Long = item.currentTime().toMillis()
        current.durationMs.store(item.duration.toMillis())
        current.positionMs.store(positionMs)
        current.bufferedPositionMs.store(bufferedEndMs(item.loadedRangesMs(), positionMs))

        val size: VideoSize? = item.presentationSize.useContents { videoSizeOrNull(width, height) }
        if (size != current.videoSize) {
            current.videoSize = size
            ifCurrent(current) { onVideoSizeChanged(size) }
        }

        val avPlayer: AVPlayer = player
        val buffering: Boolean = isStalled(avPlayer.timeControlStatus, avPlayer.reasonForWaitingToPlay)
        if (buffering != current.isBuffering) {
            current.isBuffering = buffering
            ifCurrent(current) { onBufferingChanged(buffering) }
        }

        if (item.status == AVPlayerItemStatusFailed) reportFailure(current, item.error)
    }

    private fun observeNotifications(current: Session) {
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        current.observers += center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = current.item,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            onPlayedToEnd(current)
        }
        current.observers += center.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = current.item,
            queue = NSOperationQueue.mainQueue,
        ) { notification: NSNotification? ->
            val error: NSError? =
                notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
            reportFailure(current, error)
        }
    }

    private fun onPlayedToEnd(current: Session) {
        if (session.load() !== current) return
        if (looping) {
            val avPlayer: AVPlayer = player
            avPlayer.seekToTime(kCMTimeZero.readValue())
            avPlayer.play()
            avPlayer.rate = speed
            current.positionMs.store(0L)
        } else {
            refresh(current)
            ifCurrent(current) { onCompleted() }
        }
    }

    private fun reportFailure(current: Session, error: NSError?) {
        if (session.load() !== current || current.hasFailed) return
        current.hasFailed = true
        ifCurrent(current) { onFailed(itemFailure(error)) }
    }

    /** Main thread. Leaves the shared player empty and the session inert. */
    private fun tearDown(released: Session) {
        released.monitor?.cancel()
        released.monitor = null
        released.observers.forEach { observer: NSObjectProtocol ->
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
        released.observers.clear()
        released.item.cancelPendingSeeks()
        released.asset.cancelLoading()
        val avPlayer: AVPlayer = this.avPlayer ?: return
        // `==` (isEqual:), not `===`: Kotlin/Native may hand out distinct wrappers for one
        // Objective-C object, so reference identity is not a reliable test here.
        if (avPlayer.currentItem == released.item) {
            avPlayer.pause()
            avPlayer.replaceCurrentItemWithPlayerItem(null)
        }
    }

    private fun resolveUrl(source: VideoSource): NSURL = when (source) {
        is VideoSource.Asset -> resolveAssetUrl(source.path)
            ?: throw IllegalArgumentException("Asset not found in bundle: ${source.path}")

        is VideoSource.File -> {
            require(source.path.isNotBlank()) { "File path is blank" }
            NSURL.fileURLWithPath(source.path)
        }

        // Since iOS 17 NSURL accepts almost any string (an empty one included), so a URL without
        // a scheme is refused here rather than failing later as an "unsupported URL".
        is VideoSource.Remote -> source.url.takeIf { url: String -> url.isNotBlank() }
            ?.let { url: String -> NSURL.URLWithString(url) }
            ?.takeIf { url: NSURL -> !url.scheme.isNullOrEmpty() }
            ?: throw IllegalArgumentException("Not a valid URL: ${source.url}")
    }

    private fun resolveAssetUrl(path: String): NSURL? {
        if (path.isBlank()) return null
        val name: String = path.substringBeforeLast(".")
        val extension: String = path.substringAfterLast(".", "")
        val roots: List<String?> = listOf(null) + assetSubdirectories
        return roots.firstNotNullOfOrNull { subdirectory: String? ->
            assetBundle.URLForResource(name = name, withExtension = extension, subdirectory = subdirectory)
        }
    }

    private fun activateAudioSession() {
        val audioSession: AVAudioSession = AVAudioSession.sharedInstance()
        audioSession.setCategory(
            category = AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeMoviePlayback,
            options = 0uL,
            error = null,
        )
        audioSession.setActive(true, error = null)
    }

    private fun AVPlayerItem.loadedRangesMs(): List<Pair<Long, Long>> =
        loadedTimeRanges.mapNotNull { value: Any? ->
            val range: CValue<CMTimeRange> = (value as? NSValue)?.CMTimeRangeValue ?: return@mapNotNull null
            val startMs: Long = range.useContents { secondsToMillis(CMTimeGetSeconds(start.readValue())) }
            val endMs: Long = CMTimeRangeGetEnd(range).toMillis()
            startMs to endMs
        }

    /**
     * Calls the listener only while [current] is still the loaded session — re-checked right before
     * each call, because a listener reacting to an earlier callback may have released the engine.
     */
    private inline fun ifCurrent(current: Session, call: VideoPlaybackEngineListener.() -> Unit) {
        if (session.load() === current) listener?.call()
    }

    private fun CValue<CMTime>.toMillis(): Long = secondsToMillis(CMTimeGetSeconds(this))

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1000.0

        /** `CMTime` timescale of 1/1000 s, i.e. millisecond resolution. */
        const val CM_TIME_TIMESCALE: Int = 1000
        const val STATUS_POLL_INTERVAL_MS: Long = 20L

        /** Fine enough for a position the player polls at 250 ms by default; trivial on main. */
        const val MONITOR_INTERVAL_MS: Long = 50L
        const val NORMAL_SPEED: Float = 1.0f
        const val FULL_VOLUME: Float = 1.0f
    }
}

/** An `AVPlayerItem` failure as the exception [VideoPlayerState.Error] carries. */
internal fun itemFailure(error: NSError?): IllegalStateException = IllegalStateException(
    if (error == null) {
        "AVPlayerItem failed: unknown error"
    } else {
        "AVPlayerItem failed (${error.domain} ${error.code}): ${error.localizedDescription}"
    }
)
