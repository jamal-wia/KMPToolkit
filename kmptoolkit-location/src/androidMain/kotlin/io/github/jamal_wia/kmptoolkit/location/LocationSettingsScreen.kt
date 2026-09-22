package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.activity.SystemScreenKind
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher

/**
 * The [SystemScreenKind] of the location settings screen (`Settings.ACTION_LOCATION_SOURCE_SETTINGS`)
 * that [LocationProvider.openLocationSettings] opens, for a [SystemScreenLauncher] that treats it
 * differently from other screens or logs it.
 *
 * @since 1.7.0
 */
public object LocationSettingsScreen : SystemScreenKind {
    override fun toString(): String = "LocationSettingsScreen"
}
