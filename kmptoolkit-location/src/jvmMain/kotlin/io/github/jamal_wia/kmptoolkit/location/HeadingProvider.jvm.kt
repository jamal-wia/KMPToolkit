package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Creates the desktop JVM [HeadingProvider]. A desktop has no compass, so [HeadingProvider.isAvailable]
 * is `false` and [HeadingProvider.observeHeading] never emits: the collection stays open and silent
 * until cancelled, as on an Android device without the sensor. It still validates
 * `minChangeDegrees` (a negative or non-finite value throws [IllegalArgumentException] from
 * `observeHeading` itself, before any collection), so a bug in shared code surfaces on the desktop
 * the same way as on a phone.
 *
 * Stateless: nothing is registered or held, so there is nothing to release.
 */
public fun createHeadingProvider(): HeadingProvider = NoCompassHeadingProvider

/** The desktop heading provider: stateless, one instance serves every caller and every thread. */
private object NoCompassHeadingProvider : HeadingProvider {

    override val isAvailable: Boolean = false

    @OptIn(ToolkitInternalApi::class)
    override fun observeHeading(
        minChangeDegrees: Double,
        declinationAt: GeoCoordinates?,
        screenRotation: ScreenRotation,
    ): Flow<Heading> {
        checkMinChangeDegrees(minChangeDegrees)
        return flow { awaitCancellation() }
    }
}
