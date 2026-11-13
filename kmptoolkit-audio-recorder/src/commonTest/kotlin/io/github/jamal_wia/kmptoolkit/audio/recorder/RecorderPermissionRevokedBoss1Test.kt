package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Boss round 1 of unit C: the grant changes while a recording runs. The docs make the recorder a
 * reporter, not a requester or a watcher (01-overview "Not a permission requester", 02-getting-started
 * "prepare only reports the grant"): the grant is read by `prepare()` and nowhere else. On both
 * platforms a real revocation ends the process; what the library itself must do is neither end a
 * running recording on its own nor let a later `prepare()` through.
 */
class RecorderPermissionRevokedBoss1Test {

    @Test
    fun `a grant withdrawn mid-recording ends nothing by itself and the next prepare is denied`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            fixture.engine.permissionGranted = false
            advanceTimeBy(3.seconds)
            runCurrent()
            assertIs<RecorderState.Recording>(fixture.recorder.state.value, "the recorder watched the grant")

            assertTrue(fixture.recorder.pause().isSuccess)
            assertTrue(fixture.recorder.resume().isSuccess)
            val stopped: RecorderResult<RecordedFile> = fixture.recorder.stop()
            assertEquals(path, stopped.getOrNull()?.path, "stop after the grant was withdrawn: ${stopped.errorOrNull()}")
            assertIs<RecorderState.Completed>(fixture.recorder.state.value)

            val callsBefore: Int = fixture.engine.calls.count { call: String -> call.startsWith("prepare(") }
            val denied: RecorderResult<String> = fixture.recorder.prepare(null)
            assertEquals(RecorderError.PermissionDenied, denied.errorOrNull())
            assertEquals(
                callsBefore,
                fixture.engine.calls.count { call: String -> call.startsWith("prepare(") },
                "the engine was prepared without a grant",
            )
            assertIs<RecorderState.Failed>(fixture.recorder.state.value)

            // The grant comes back: the same recorder records again without being recreated.
            fixture.engine.permissionGranted = true
            assertTrue(fixture.recorder.prepare(null).isSuccess)
            assertTrue(fixture.recorder.start().isSuccess)
            assertFalse(fixture.recorder.state.value is RecorderState.Failed)
        }
}
