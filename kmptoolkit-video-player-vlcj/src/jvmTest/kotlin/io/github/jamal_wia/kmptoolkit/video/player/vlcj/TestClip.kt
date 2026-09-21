package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.io.File
import java.nio.file.Paths

/** The checked-in test clip: 64×48, 2 s, H.264 in MP4, a few KB (`src/jvmTest/resources/video`). */
internal object TestClip {
    const val ASSET_PATH: String = "video/clip-64x48-2s.mp4"
    const val WIDTH: Int = 64
    const val HEIGHT: Int = 48
    const val DURATION_MS: Long = 2_000L

    val file: File
        get() = Paths.get(checkNotNull(TestClip::class.java.classLoader.getResource(ASSET_PATH)).toURI()).toFile()
}
