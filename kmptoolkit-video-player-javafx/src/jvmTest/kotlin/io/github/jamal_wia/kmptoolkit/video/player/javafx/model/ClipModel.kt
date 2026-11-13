package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

/**
 * The source a [ScriptedNativePlayer] pretends to play: the numbers of the checked-in test clip, as
 * `docs/kmptoolkit-video-player-javafx/06-testing.md` documents it.
 *
 * It is data only. The parts of the scripted player read it: the status model gives [durationMs], [width]
 * and [height] at ready, the time part (J6b) lands seeks on [keyFrameIntervalMs] boundaries, the picture
 * part (J7b) shows the first opaque picture [firstPictureAfterReadyMs] after ready.
 *
 * @param durationMs `media.duration` once the player is ready, milliseconds.
 * @param keyFrameIntervalMs the distance between key frames (`-g 15` at 15 fps is 1000 ms).
 * @param width `media.width` once ready.
 * @param height `media.height` once ready.
 * @param firstPictureAfterReadyMs how long after ready the first decoded, opaque picture exists.
 */
internal data class ClipModel(
    val durationMs: Double = 3_000.0,
    val keyFrameIntervalMs: Double = 1_000.0,
    val width: Int = 64,
    val height: Int = 48,
    val firstPictureAfterReadyMs: Double = 40.0,
) {
    init {
        require(durationMs > 0.0 && durationMs.isFinite()) { "durationMs must be finite and positive, was $durationMs" }
        require(keyFrameIntervalMs > 0.0 && keyFrameIntervalMs.isFinite()) {
            "keyFrameIntervalMs must be finite and positive, was $keyFrameIntervalMs"
        }
        require(width > 0 && height > 0) { "the size must be positive, was ${width}x$height" }
        require(firstPictureAfterReadyMs >= 0.0 && firstPictureAfterReadyMs.isFinite()) {
            "firstPictureAfterReadyMs must be finite and not negative, was $firstPictureAfterReadyMs"
        }
    }
}
