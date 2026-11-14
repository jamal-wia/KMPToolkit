package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.testing.FakeVideoPlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 14, questions 1 and 3 on the compose layer, from `04-api-reference.md` (`rememberOwnedVideoPlayer`): "Creation:
 * once, on first composition ... `config` and the factory are read then and never again"; "each `source` (by `equals`) is
 * prepared"; "release() when the call leaves the composition". A screen that recomposes many times with a new but equal
 * source, another config and another factory creates one player, prepares once per distinct source, and releases that
 * player exactly once.
 */
@OptIn(ExperimentalTestApi::class)
abstract class OwnedPlayerCreationOnceD1R14Tests {

    @Test
    fun `many recompositions with an equal source another config and another factory create one player and release it once`() = runComposeUiTest {
        val engines: MutableList<FakeVideoPlaybackEngine> = mutableListOf()
        val configsSeen: MutableList<VideoPlayerConfig> = mutableListOf()
        val firstFactory = VideoPlayerFactory { config ->
            configsSeen += config
            val engine = FakeVideoPlaybackEngine()
            engines += engine
            createVideoPlayer(engine, config)
        }
        val otherFactoryCalls: MutableList<Int> = mutableListOf()
        val otherFactory = VideoPlayerFactory { config ->
            otherFactoryCalls += 1
            createVideoPlayer(FakeVideoPlaybackEngine(), config)
        }
        var tick by mutableStateOf(0)
        var shown by mutableStateOf(true)
        var captured: VideoPlayer? = null
        try {
            setContent {
                val useOther: Boolean = tick % 2 == 1
                TestHost(factory = if (useOther) otherFactory else firstFactory) {
                    if (shown) {
                        // A new but equal source instance on every recomposition, and a new config.
                        val source = VideoSource.Remote("https://example.test/a.mp4")
                        val config = VideoPlayerConfig(maxPlaybackSpeed = 2f + tick)
                        captured = rememberVideoPlayer(source = source, autoPlay = false, config = config)
                    }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { captured?.stateFlow?.value is VideoPlayerState.Ready }
            val player: VideoPlayer = checkNotNull(captured)
            repeat(30) {
                tick += 1
                waitForIdle()
            }
            assertTrue(captured === player, "a recomposition replaced the player")
            assertEquals(1, engines.size, "the first factory built more than one player")
            assertEquals(emptyList(), otherFactoryCalls, "a later factory was consulted")
            assertEquals(2f, configsSeen.single().maxPlaybackSpeed, "the config is read once")
            assertEquals(2f, player.playbackSpeedRange.endInclusive)
            assertEquals(1, engines[0].loadedSources.size, "an equal source was prepared again")
            shown = false
            waitForIdle()
            waitUntil(timeoutMillis = WAIT_MS) { engines[0].disposeCount == 1 }
            waitForIdle()
            assertEquals(1, engines[0].disposeCount)
            assertEquals(1, engines.size, "leaving built another player")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        } finally {
            captured?.release()
        }
    }

    @Test
    fun `fifty distinct sources prepare each once and the last one is loaded and leaving releases once`() = runComposeUiTest {
        val engines: MutableList<FakeVideoPlaybackEngine> = mutableListOf()
        val factory = VideoPlayerFactory { config ->
            val engine = FakeVideoPlaybackEngine()
            engines += engine
            createVideoPlayer(engine, config)
        }
        var index by mutableStateOf(0)
        var shown by mutableStateOf(true)
        var captured: VideoPlayer? = null
        try {
            setContent {
                TestHost(factory = factory) {
                    if (shown) captured = rememberVideoPlayer(source = VideoSource.Remote("https://example.test/$index.mp4"), autoPlay = true)
                }
            }
            for (next in 1..50) {
                waitUntil(timeoutMillis = WAIT_MS) { captured?.stateFlow?.value is VideoPlayerState.Playing }
                index = next
                waitForIdle()
            }
            waitUntil(timeoutMillis = WAIT_MS) { engines.single().loadedSources.lastOrNull() == VideoSource.Remote("https://example.test/50.mp4") }
            waitUntil(timeoutMillis = WAIT_MS) { captured?.stateFlow?.value is VideoPlayerState.Playing }
            assertEquals(1, engines.size)
            val loaded: List<VideoSource> = engines[0].loadedSources
            assertEquals(loaded.distinct(), loaded, "a source was prepared twice")
            assertEquals(VideoSource.Remote("https://example.test/50.mp4"), loaded.last())
            shown = false
            waitForIdle()
            waitUntil(timeoutMillis = WAIT_MS) { engines[0].disposeCount == 1 }
            assertEquals(1, engines[0].disposeCount)
            assertTrue(!engines[0].isPlaying)
        } finally {
            captured?.release()
        }
    }
}

private const val WAIT_MS: Long = 10_000L
