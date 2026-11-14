package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/** The round 10 engine transcript on the Android driver. */
@RunWith(AndroidJUnit4::class)
class EngineTranscriptRound10AdversarialTest : EngineTranscriptRound10Base() {
    override fun newStorage(): UploaderStorage = createInMemoryUploaderStorage()
}
