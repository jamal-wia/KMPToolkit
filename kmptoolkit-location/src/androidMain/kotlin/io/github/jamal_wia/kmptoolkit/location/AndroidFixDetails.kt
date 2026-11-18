package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * What `android.location.Location` reports beyond the common [LocationFix] fields.
 *
 * @property provider The provider that produced the fix (`"gps"`, `"network"`, `"fused"`, `"passive"`
 * or an OEM name); `""` when the platform reported none.
 * @property elapsedRealtimeNanos `Location.getElapsedRealtimeNanos`: the boot-clock stamp, for
 * same-boot age and sensor fusion. Meaningless after a reboot.
 */
@OptIn(ToolkitInternalApi::class)
public data class AndroidFixDetails(
    public val provider: String,
    public val elapsedRealtimeNanos: Long,
) : PlatformFixDetails

/** This fix's Android details, or `null` when it did not come from the Android provider. */
public val LocationFix.androidDetails: AndroidFixDetails?
    get() = platformDetails as? AndroidFixDetails
