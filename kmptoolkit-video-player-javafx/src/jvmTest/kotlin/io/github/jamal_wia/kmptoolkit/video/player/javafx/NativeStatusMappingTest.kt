package io.github.jamal_wia.kmptoolkit.video.player.javafx

import javafx.scene.media.MediaPlayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port's [NativeStatus] must read exactly as `MediaPlayer.Status` did (design 2.3 item 6: `inspect()` returns the
 * status text, and tests compare it). Loads only the enum, so it runs headless and never starts the toolkit.
 */
class NativeStatusMappingTest {

    @Test
    fun theNativeStatusNamesAreTheMediaPlayerStatusNamesInTheSameOrder() {
        assertEquals(
            MediaPlayer.Status.entries.map { it.name },
            NativeStatus.entries.map { it.name },
        )
    }

    @Test
    fun everyMediaPlayerStatusMapsToTheNativeStatusOfTheSameName() {
        for (status: MediaPlayer.Status in MediaPlayer.Status.entries) {
            assertEquals(status.name, status.toNative().name, "status $status")
            assertEquals(status.toString(), status.toNative().toString(), "text of $status")
        }
    }

    @Test
    fun aMissingStatusReadsAsUnknown() {
        assertEquals(NativeStatus.UNKNOWN, (null as MediaPlayer.Status?).toNative())
    }
}
