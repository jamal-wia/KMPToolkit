package io.github.jamal_wia.kmptoolkit.hardwarekeys

import androidx.compose.runtime.Composable

@Composable
public actual fun DialogWindowHardwareKeyEffect() {
    // iOS has no hardware-key dispatch to intercept: the volume buttons are handled by the system and
    // never delivered to the app, and sheets share the root view controller.
}
