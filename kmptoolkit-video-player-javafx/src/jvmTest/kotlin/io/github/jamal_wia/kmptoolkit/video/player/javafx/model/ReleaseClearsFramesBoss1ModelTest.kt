package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * D2 boss 1, mutant K9 (the frame flow not cleared when a session closes): `VideoFrameSource.frames` is "the latest decoded
 * frame, or `null` when there is none". A released engine has no picture, so a Compose surface drawing the flow must not
 * keep showing the last frame of the released source.
 */
@OptIn(ToolkitInternalApi::class)
class ReleaseClearsFramesBoss1ModelTest {

    @Test
    fun releaseClearsTheLatestFrame() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = StatusScript()), timeScript = TimeScript())
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        engine.start()
        advanceBy(500)
        assertNotNull(engine.frames.value, "no frame was published while playing, so the premise of the test does not hold")
        engine.release()
        advanceBy(50)
        assertNull(engine.frames.value, "the last frame of a released source is still published")
    }
}
