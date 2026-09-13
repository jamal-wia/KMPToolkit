package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport

/**
 * An [UploadTransport] that records hand-offs instead of uploading, for testing an `UploadHandler`
 * and the code that enqueues its items.
 *
 * Nothing is settled on its own. Drive the outcome yourself through `UploadGateway` — prepare the
 * attempt, report progress, complete it — which exercises exactly what a platform transport would:
 *
 * ```kotlin
 * val transport = RecordingUploadTransport()
 * val engine = createUploaderEngine(store, listOf(AvatarUploadHandler(transport)), backgroundScope)
 * UploaderEngineRegistry.register(engine)
 *
 * engine.enqueue(handler, avatar)
 * engine.drain()
 * assertEquals(1, transport.launches.size)
 *
 * UploadGateway.complete(transport.launches.single().itemId, UploadResult.Completed(201))
 * ```
 *
 * **Not thread-safe**, like the other doubles here.
 *
 * @param leaseMillis the lease hand-offs are claimed under.
 * @since 1.5.0
 */
public class RecordingUploadTransport(
    override val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
) : UploadTransport {

    /** One hand-off: the item, whether it was a re-hand after an expired lease, and the request. */
    public data class Launch(val itemId: String, val isRehandOff: Boolean, val request: UploadRequest)

    private val recorded: MutableList<Launch> = mutableListOf()

    /** Every hand-off so far, oldest first. A snapshot. */
    public val launches: List<Launch> get() = recorded.toList()

    /** How many times [cancelAll] was called. */
    public var cancelAllCount: Int = 0
        private set

    /** Thrown from the next and every later launch while set — a platform scheduler that is unavailable. */
    public var failLaunchWith: Throwable? = null

    override fun launch(itemId: String, request: UploadRequest) {
        launch(itemId, isRehandOff = false, request = request)
    }

    override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        failLaunchWith?.let { throw it }
        recorded += Launch(itemId, isRehandOff, request)
    }

    override fun cancelAll() {
        cancelAllCount++
    }

    /** The default lease: long enough that a test's clock never expires it by accident. */
    public companion object {
        public const val DEFAULT_LEASE_MILLIS: Long = 15L * 60L * 1_000L
    }
}
