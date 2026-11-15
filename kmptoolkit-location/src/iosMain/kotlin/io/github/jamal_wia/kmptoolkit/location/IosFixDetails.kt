package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * What `CLLocation` reports beyond the common [LocationFix] fields. Implemented only by this
 * library; read it through [iosDetails] in iOS code.
 *
 * @property floorLevel the building floor (`CLFloor.level`) when the venue provides one; `null`
 * otherwise.
 * @property isProducedByAccessory whether an external accessory such as a GNSS receiver produced the
 * fix (iOS 15+); `null` below iOS 15.
 */
@OptIn(ToolkitInternalApi::class)
public data class IosFixDetails(
    public val floorLevel: Int?,
    public val isProducedByAccessory: Boolean?,
) : PlatformFixDetails

/** This fix's iOS details, or `null` when it did not come from the iOS provider. */
public val LocationFix.iosDetails: IosFixDetails?
    get() = platformDetails as? IosFixDetails
