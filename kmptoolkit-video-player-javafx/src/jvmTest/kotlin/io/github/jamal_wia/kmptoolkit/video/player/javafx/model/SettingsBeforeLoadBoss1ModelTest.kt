package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D2 boss 1, mutant K8 (the re-apply of looping after a load removed): settings given to the engine before any source is
 * loaded have no player to reach, so `load()` applies the latest of them once the player exists ("Settings may have
 * changed while the player was being built; apply the latest"). Looping, volume and speed given before the first load
 * are all in force on the new native player.
 */
class SettingsBeforeLoadBoss1ModelTest {

    @Test
    fun loopingVolumeAndSpeedGivenBeforeTheFirstLoadAreInForceOnTheNewPlayer() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = StatusScript()), timeScript = TimeScript())
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        engine.setLooping(true)
        engine.setVolume(0.25f)
        engine.setSpeed(1.5f)
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            advanceBy(50)
            val native: ScriptedNativePlayer = backend.last.player
            assertEquals(-1, native.cycleCount, "looping given before the first load is not in force: ${native.log.tail(8)}")
            assertEquals(0.25, native.volume, 1e-6, "the volume given before the first load is not in force")
            assertEquals(1.5, native.rate, 1e-6, "the speed given before the first load is not in force")
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    /** Mutant K11 (the NaN guard of `setVolume` removed): NaN is not a volume; the engine keeps the last volume, also across a reload. */
    @Test
    fun aVolumeOfNaNIsIgnoredAndTheLastVolumeStaysInForceAcrossAReload() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, script = StatusScript()), timeScript = TimeScript())
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        engine.setVolume(0.5f)
        engine.setVolume(Float.NaN)
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            advanceBy(50)
            assertEquals(0.5, backend.last.player.volume, 1e-6, "a NaN volume replaced the last volume")
            engine.setVolume(Float.NaN)
            advanceBy(50)
            assertEquals(0.5, backend.last.player.volume, 1e-6, "a NaN volume reached the loaded player")
        } finally {
            engine.release()
            advanceBy(50)
        }
    }
}
