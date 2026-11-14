package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

/** The round 10 engine transcript on the iOS driver. */
class EngineTranscriptRound10IosAdversarialTest : EngineTranscriptRound10Base() {
    override fun newStorage(): UploaderStorage = createInMemoryUploaderStorage()
}
