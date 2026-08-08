package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.files.FilePicker
import io.github.jamal_wia.kmptoolkit.platform.files.PickResult

/**
 * A [FilePicker] that returns whatever the test says, without a chooser.
 *
 * The outcomes worth exercising are the ones a manual tester rarely reproduces —
 * [PickResult.Cancelled] (the most common outcome in the wild), [PickResult.TooLarge] and
 * [PickResult.Failed]:
 *
 * ```kotlin
 * val picker = FakeFilePicker(PickResult.Cancelled)
 * uploader.attach()
 * assertEquals(UploadState.Idle, uploader.state)
 * ```
 *
 * @param result what [pick] returns. Defaults to [PickResult.Cancelled] — the outcome a user
 *   produces most often, and the one code most often forgets.
 */
public class FakeFilePicker(
    public var result: PickResult = PickResult.Cancelled,
) : FilePicker {

    private val mutableRequests: MutableList<List<String>> = mutableListOf()

    /**
     * The MIME filter of every [pick] call, in order. Assert on it to prove a screen asked for
     * images rather than for anything on the device.
     */
    public val requestedMimeTypes: List<List<String>> get() = mutableRequests.toList()

    /** How many times [pick] was called. */
    public val pickCount: Int get() = mutableRequests.size

    override suspend fun pick(mimeTypes: List<String>): PickResult {
        mutableRequests.add(mimeTypes.toList())
        return result
    }
}
