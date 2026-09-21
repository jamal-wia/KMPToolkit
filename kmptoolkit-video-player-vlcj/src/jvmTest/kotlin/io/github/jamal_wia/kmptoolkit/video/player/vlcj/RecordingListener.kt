package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.util.concurrent.CopyOnWriteArrayList

/** Records every engine callback, from whatever thread VLC delivers it on. */
internal class RecordingListener : VideoPlaybackEngineListener {

    val events: MutableList<String> = CopyOnWriteArrayList()
    val failures: MutableList<Throwable> = CopyOnWriteArrayList()
    val sizes: MutableList<VideoSize?> = CopyOnWriteArrayList()

    val completions: Int get() = events.count { it == "completed" }

    override fun onCompleted() {
        events += "completed"
    }

    override fun onFailed(cause: Throwable) {
        failures += cause
        events += "failed"
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        events += "buffering=$isBuffering"
    }

    override fun onVideoSizeChanged(size: VideoSize?) {
        sizes += size
        events += "size=$size"
    }
}
