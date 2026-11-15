@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.Heading
import io.github.jamal_wia.kmptoolkit.location.HeadingProvider
import io.github.jamal_wia.kmptoolkit.location.ScreenRotation
import io.github.jamal_wia.kmptoolkit.location.checkMinChangeDegrees
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A [HeadingProvider] a test drives by hand. Safe to call from any thread.
 *
 * Like the real providers it validates `minChangeDegrees` (a negative or non-finite value throws
 * [IllegalArgumentException] from [observeHeading] itself, before any collection), and like
 * [FakeLocationProvider] it applies **no policy**: the filter, the declination and the rotation are
 * only recorded ([activeFilters], [activeDeclinationAt], [activeScreenRotations]), never applied, so
 * a test emits exactly the headings it wants delivered.
 *
 * ### The stream
 * Every collector reads one shared, conflated source that replays its latest heading: a collector
 * that starts after an [emit] gets that heading first, a busy one skips intermediate headings and
 * ends on the newest. There is **no seed**: a real heading provider emits only when the sensor
 * reports, so collectors wait until the first [emit]. The stream never completes on its own.
 *
 * ### A device without a compass
 * When [isAvailable] is `false`, [emit] does nothing and [observeHeading] stays silent, as a real
 * device does: the collector is live (counted in [activeObservers], released on cancellation) and
 * receives nothing.
 *
 * @param isAvailable what [HeadingProvider.isAvailable] reports; fixed for the life of the fake,
 * as on a device.
 */
public class FakeHeadingProvider(isAvailable: Boolean = true) : HeadingProvider {

    override val isAvailable: Boolean = isAvailable

    // Replay 1 + DROP_OLDEST, as in FakeLocationProvider; no initial value (no seed). tryEmit always
    // succeeds with DROP_OLDEST and is thread-safe.
    private val stream: MutableSharedFlow<Heading> = MutableSharedFlow<Heading>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val collectors: FakeRecorder<Registration> = FakeRecorder()

    /**
     * Makes [heading] the latest value: delivered to every live collector (conflated) and replayed
     * to later ones. Nothing is emitted while [isAvailable] is `false`, as on a real device.
     */
    public fun emit(heading: Heading) {
        // No isAvailable check needed: a collector of an unavailable fake never subscribes to the
        // stream, so a heading published here reaches nobody (FakeHeadingProviderTest pins that).
        stream.tryEmit(heading)
    }

    /** The `minChangeDegrees` of every live collector, oldest first. Recorded, never applied. */
    public val activeFilters: List<Double>
        get() = collectors.snapshot().map { it.minChangeDegrees }

    /** The `declinationAt` of every live collector, oldest first. Recorded, never applied. */
    public val activeDeclinationAt: List<GeoCoordinates?>
        get() = collectors.snapshot().map { it.declinationAt }

    /** The `screenRotation` of every live collector, oldest first. Recorded, never applied. */
    public val activeScreenRotations: List<ScreenRotation>
        get() = collectors.snapshot().map { it.screenRotation }

    /**
     * Live [observeHeading] collectors: counted from the moment a collection starts until it ends
     * for any reason (cancelled, failed). `0` proves release.
     */
    public val activeObservers: StateFlow<Int> = collectors.count

    override fun observeHeading(
        minChangeDegrees: Double,
        declinationAt: GeoCoordinates?,
        screenRotation: ScreenRotation,
    ): Flow<Heading> {
        checkMinChangeDegrees(minChangeDegrees)
        return flow {
            // Registered when the collection starts, released on every way out of it. Nothing is
            // held between collections.
            val registration: FakeRecorder.Entry<Registration> =
                collectors.add(Registration(minChangeDegrees, declinationAt, screenRotation))
            try {
                // No compass: stay open and silent, like a device without the sensor.
                if (!isAvailable) awaitCancellation()
                emitAll(stream)
            } finally {
                collectors.remove(registration)
            }
        }
    }

    private class Registration(
        val minChangeDegrees: Double,
        val declinationAt: GeoCoordinates?,
        val screenRotation: ScreenRotation,
    )
}
