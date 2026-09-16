package io.github.jamal_wia.kmptoolkit.hardwarekeys

import androidx.compose.runtime.Composable

@Composable
public actual fun DialogWindowHardwareKeyEffect() {
    // Desktop has no Android-style routing of hardware keys to a separate PhoneWindow, so there is
    // nothing to install. The target exists so that shared dialog code compiles — see the module's
    // build file.
}
