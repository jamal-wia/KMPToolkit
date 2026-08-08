package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The documented defaults, and the argument checks that turn a nonsensical encoder setting into a
 * failure at the call site instead of an opaque one inside a platform encoder minutes later.
 */
class AudioRecorderConfigTest {

    @Test
    fun `the defaults match what the documentation promises`() {
        val config = AudioRecorderConfig()

        assertEquals(AudioFormat.M4A, config.format)
        assertEquals(44_100, config.sampleRate)
        assertEquals(1, config.channelCount)
        assertEquals(128_000, config.bitRate)
        assertEquals(100.milliseconds, config.durationUpdateInterval)
        assertEquals(8L * 1024 * 1024, config.minimumFreeSpaceBytes)
    }

    @Test
    fun `the default storage derives everything from the consumer`() {
        val storage = AudioRecorderConfig().storage

        assertNull(storage.directoryPath, "no directory is hardcoded by the library")
        assertNull(storage.directoryName, "the app id is resolved at runtime rather than fixed")
        assertEquals("recording", storage.fileNamePrefix)
    }

    @Test
    fun `HIGH_QUALITY raises the encoder settings and leaves the rest alone`() {
        val config = AudioRecorderConfig.HIGH_QUALITY

        assertEquals(48_000, config.sampleRate)
        assertEquals(2, config.channelCount)
        assertEquals(256_000, config.bitRate)
        assertEquals(AudioFormat.M4A, config.format)
        assertEquals(AudioRecorderConfig().storage, config.storage)
    }

    @Test
    fun `each format reports the extension its file name will use`() {
        assertEquals("m4a", AudioFormat.M4A.extension)
        assertEquals("aac", AudioFormat.AAC.extension)
        assertEquals("wav", AudioFormat.WAV.extension)
    }

    @Test
    fun `a non positive sample rate is rejected`() {
        assertFailsWith<IllegalArgumentException> { AudioRecorderConfig(sampleRate = 0) }
        assertFailsWith<IllegalArgumentException> { AudioRecorderConfig(sampleRate = -1) }
    }

    @Test
    fun `a non positive channel count is rejected`() {
        assertFailsWith<IllegalArgumentException> { AudioRecorderConfig(channelCount = 0) }
    }

    @Test
    fun `a non positive bit rate is rejected`() {
        assertFailsWith<IllegalArgumentException> { AudioRecorderConfig(bitRate = 0) }
    }

    @Test
    fun `a non positive tick interval is rejected because it would spin`() {
        assertFailsWith<IllegalArgumentException> {
            AudioRecorderConfig(durationUpdateInterval = Duration.ZERO)
        }
    }

    @Test
    fun `a negative free space minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioRecorderConfig(minimumFreeSpaceBytes = -1L)
        }
    }

    @Test
    fun `a zero free space minimum is allowed as the way to switch the check off`() {
        assertEquals(0L, AudioRecorderConfig(minimumFreeSpaceBytes = 0L).minimumFreeSpaceBytes)
    }

    @Test
    fun `a blank file name prefix is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecordingStorage(fileNamePrefix = "") }
        assertFailsWith<IllegalArgumentException> { RecordingStorage(fileNamePrefix = "   ") }
    }

    @Test
    fun `a file name prefix containing a path separator is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecordingStorage(fileNamePrefix = "a/b") }
        assertFailsWith<IllegalArgumentException> { RecordingStorage(fileNamePrefix = "a\\b") }
    }

    @Test
    fun `a blank directory is rejected while null keeps the default`() {
        assertFailsWith<IllegalArgumentException> { RecordingStorage(directoryPath = " ") }
        assertFailsWith<IllegalArgumentException> { RecordingStorage(directoryName = " ") }
        assertNull(RecordingStorage(directoryPath = null).directoryPath)
    }
}
