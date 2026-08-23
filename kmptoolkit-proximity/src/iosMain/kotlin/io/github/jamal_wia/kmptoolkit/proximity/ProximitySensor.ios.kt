package io.github.jamal_wia.kmptoolkit.proximity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Creates the iOS [ProximitySensor].
 *
 * iOS has no proximity API this library can rest on: Core Motion offers none, and `UIDevice`'s
 * monitoring is iPhone-only and couples the sensor to blanking the screen. So the returned instance
 * simply reports itself absent, and consumers fall back exactly as they do on an Android device
 * without one — see `docs/kmptoolkit-proximity/05-platform-notes.md`.
 *
 * The returned instance is stateless; calling this repeatedly is free of consequence.
 */
public fun createProximitySensor(): ProximitySensor = IosProximitySensor

private object IosProximitySensor : ProximitySensor {

    override val isAvailable: Boolean get() = false

    override fun observe(): Flow<Boolean> = emptyFlow()
}
