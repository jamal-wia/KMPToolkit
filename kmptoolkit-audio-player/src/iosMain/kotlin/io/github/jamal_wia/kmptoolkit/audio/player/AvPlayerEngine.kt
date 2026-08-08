package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol

/**
 * [PlaybackEngine] backed by `AVFoundation.AVPlayer`.
 *
 * @param assetBundle bundle searched for [AudioSource.Asset]; see `createAudioPlayer` in this
 *   source set.
 * @param assetSubdirectories bundle subdirectories searched after the bundle root.
 * @param managesAudioSession whether to switch the shared `AVAudioSession` to the playback category.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvPlayerEngine(
    private val assetBundle: NSBundle,
    private val assetSubdirectories: List<String>,
    private val managesAudioSession: Boolean,
) : PlaybackEngine {

    private var player: AVPlayer? = null
    private var completionObserver: NSObjectProtocol? = null
    private var listener: PlaybackEngineListener? = null

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: AudioSource) {
        release()

        val url: NSURL = resolveUrl(source)

        withContext(Dispatchers.Main) {
            if (managesAudioSession) activateAudioSession()

            val item = AVPlayerItem(uRL = url)
            player = AVPlayer(playerItem = item)
            observeCompletion(item)
        }

        awaitReadyToPlay()
    }

    override fun start() {
        val current: AVPlayer = player ?: return
        current.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        val current: AVPlayer = player ?: return
        current.seekToTime(
            CMTimeMakeWithSeconds(
                seconds = positionMs / MILLIS_PER_SECOND,
                preferredTimescale = CM_TIME_TIMESCALE,
            )
        )
    }

    override fun setSpeed(speed: Float) {
        val current: AVPlayer = player ?: return
        // Assigning a non-zero rate to a paused AVPlayer resumes it, so the rate is only pushed
        // while it is already playing; the player above re-applies it on the next start().
        if (current.timeControlStatus == AVPlayerTimeControlStatusPlaying) current.rate = speed
    }

    override fun durationMs(): Long = player?.currentItem?.duration.toMillis()

    override fun positionMs(): Long = player?.currentTime().toMillis()

    override fun release() {
        completionObserver?.let { observer: NSObjectProtocol ->
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
        completionObserver = null
        player?.pause()
        player = null
    }

    /**
     * Polls `AVPlayerItem.status` until the item is playable or has failed.
     *
     * Polling rather than KVO: `addObserver(forKeyPath:)` has no Kotlin/Native-safe binding that
     * does not risk observing a deallocated object, and this loop runs only during loading, at most
     * a few dozen iterations, then stops. It is also naturally cancellable, which is what makes
     * `prepare()`'s cancellation contract hold on iOS.
     */
    private suspend fun awaitReadyToPlay() {
        while (true) {
            val item: AVPlayerItem = player?.currentItem ?: error("Player was released while loading")
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> return
                AVPlayerItemStatusFailed -> error(
                    "AVPlayerItem failed: " + (item.error?.localizedDescription ?: "unknown error")
                )

                else -> delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun resolveUrl(source: AudioSource): NSURL = when (source) {
        is AudioSource.Asset -> resolveAssetUrl(source.path)
            ?: throw IllegalArgumentException("Asset not found in bundle: ${source.path}")

        is AudioSource.File -> NSURL.fileURLWithPath(source.path)
        is AudioSource.Remote -> NSURL.URLWithString(source.url)
            ?: throw IllegalArgumentException("Not a valid URL: ${source.url}")
    }

    private fun resolveAssetUrl(path: String): NSURL? {
        val name: String = path.substringBeforeLast(".")
        val extension: String = path.substringAfterLast(".", "")
        val roots: List<String?> = listOf(null) + assetSubdirectories
        return roots.firstNotNullOfOrNull { subdirectory: String? ->
            assetBundle.URLForResource(
                name = name,
                withExtension = extension,
                subdirectory = subdirectory,
            )
        }
    }

    private fun activateAudioSession() {
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    private fun observeCompletion(item: AVPlayerItem) {
        completionObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = null,
        ) { _ ->
            listener?.onCompleted()
        }
    }

    private fun CValue<CMTime>?.toMillis(): Long {
        val time: CValue<CMTime> = this ?: return 0L
        val seconds: Double = CMTimeGetSeconds(time)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return 0L
        return (seconds * MILLIS_PER_SECOND).toLong()
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1000.0

        /** `CMTime` timescale of 1/1000 s, i.e. millisecond resolution. */
        const val CM_TIME_TIMESCALE: Int = 1000
        const val STATUS_POLL_INTERVAL_MS: Long = 20L
    }
}
