package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Shared scaffolding for the Robolectric tests of the Media3 engine.
 *
 * The engine under test is the production one; only its two seams are swapped: the `ExoPlayer` is
 * Media3's own `TestExoPlayerBuilder` player (fake renderers, a fake clock that advances by itself),
 * and the source is a `FakeMediaSource` of a known length and picture format — Robolectric has no
 * decoders, so a real file could not reach READY here. The test thread is Robolectric's main
 * thread, which is the engine's looper thread, so the helpers below pump the main looper while they
 * wait.
 */
internal object Media3TestSupport {

    /** Five seconds of video, 1000×1000 stored pixels that display 1.5 times as wide. */
    const val SOURCE_DURATION_MS: Long = 5_000L

    val anamorphicVideo: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1_000)
        .setHeight(1_000)
        .setPixelWidthHeightRatio(1.5f)
        .build()

    fun fakeSource(durationMs: Long = SOURCE_DURATION_MS): FakeMediaSource = FakeMediaSource(
        FakeTimeline(
            FakeTimeline.TimelineWindowDefinition(
                /* isSeekable = */ true,
                /* isDynamic = */ false,
                /* durationUs = */ durationMs * 1_000L,
            ),
        ),
        anamorphicVideo,
    )

    /** An engine over a test ExoPlayer; [created] receives the player once the engine builds it. */
    fun newEngine(
        context: Context,
        created: (ExoPlayer) -> Unit = {},
        mediaSources: (Context, VideoSource) -> MediaSource = { _, _ -> fakeSource() },
    ): Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = Looper.getMainLooper(),
        playerFactory = { playerContext: Context, looper: Looper ->
            TestExoPlayerBuilder(playerContext).setLooper(looper).build().also(created)
        },
        mediaSourceFactory = mediaSources,
    )

    /** Starts [block] without waiting; pump the looper with [runMainLooperUntil] to let it finish. */
    @OptIn(DelicateCoroutinesApi::class)
    fun launchOnMain(block: suspend () -> Unit): Job = GlobalScope.launch(Dispatchers.Unconfined) { block() }

    /** Runs [block] to completion, pumping the main looper meanwhile; returns its outcome. */
    fun <T> runSuspending(block: suspend () -> T): Result<T> {
        var outcome: Result<T>? = null
        val job: Job = launchOnMain { outcome = runCatching { block() } }
        runMainLooperUntil { job.isCompleted }
        return requireNotNull(outcome)
    }

    fun runMainLooperUntil(condition: () -> Boolean) {
        RobolectricUtil.runMainLooperUntil { condition() }
    }

    /**
     * Gives [player] somewhere to draw, as the Compose surface does. Media3's fake video renderer —
     * like a real one — reports a picture size only once it has an output.
     */
    fun attachSurface(player: Player) {
        player.setVideoSurface(Surface(SurfaceTexture(0)))
    }

    /** Calls [block] on a fresh background thread — the thread the player's polling runs on. */
    fun <T> offLooper(block: () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching(block) }.join()
        return requireNotNull(result).getOrThrow()
    }
}

/** Records every engine callback, in order, as the tests' eyes on what the engine reported. */
internal class RecordingEngineListener : VideoPlaybackEngineListener {

    val completions: MutableList<Unit> = mutableListOf()
    val failures: MutableList<Throwable> = mutableListOf()
    val buffering: MutableList<Boolean> = mutableListOf()
    val sizes: MutableList<VideoSize?> = mutableListOf()

    override fun onCompleted() {
        completions += Unit
    }

    override fun onFailed(cause: Throwable) {
        failures += cause
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        buffering += isBuffering
    }

    override fun onVideoSizeChanged(size: VideoSize?) {
        sizes += size
    }
}
