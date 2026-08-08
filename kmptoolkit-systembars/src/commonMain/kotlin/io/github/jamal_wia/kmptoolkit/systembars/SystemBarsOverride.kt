package io.github.jamal_wia.kmptoolkit.systembars

/**
 * A claim on some of the three axes of [SystemBarsConfig], for as long as its owner is alive.
 *
 * `null` on an axis means **"not mine"** — the value underneath shows through untouched. That is
 * what makes two simultaneous overrides safe: a media viewer claiming
 * [visibility] and a header claiming [statusBarIcons] are not in conflict and neither
 * one has to know the other exists.
 *
 * An override is never applied by writing into the configuration. It is *pushed* onto the
 * controller's layer stack with [SystemBarsController.applyOverride] and removed again with the
 * handle it returns, so removing it restores exactly the state that was underneath — even if that
 * state changed while the override was live. See `docs/kmptoolkit-systembars/03-guide.md`.
 *
 * @property statusBarIcons claimed icon style for the status bar, or `null` to leave it alone.
 * @property navigationBarIcons claimed icon style for the navigation bar, or `null`.
 * @property visibility claimed bar visibility, or `null`.
 */
public data class SystemBarsOverride(
    val statusBarIcons: SystemBarIconStyle? = null,
    val navigationBarIcons: SystemBarIconStyle? = null,
    val visibility: SystemBarsVisibility? = null,
) {

    /** True when this override claims no axis at all and therefore changes nothing. */
    public val isEmpty: Boolean
        get() = statusBarIcons == null && navigationBarIcons == null && visibility == null

    public companion object {

        /** An override that claims nothing. Useful as a "not yet decided" starting value. */
        public val None: SystemBarsOverride = SystemBarsOverride()

        /** Claims **both** icon axes, leaving visibility alone. */
        public fun icons(style: SystemBarIconStyle): SystemBarsOverride = SystemBarsOverride(
            statusBarIcons = style,
            navigationBarIcons = style,
        )
    }
}

/** Applies the axes this override claims on top of [config], leaving the rest as they were. */
internal fun SystemBarsOverride.applyTo(config: SystemBarsConfig): SystemBarsConfig = SystemBarsConfig(
    statusBarIcons = statusBarIcons ?: config.statusBarIcons,
    navigationBarIcons = navigationBarIcons ?: config.navigationBarIcons,
    visibility = visibility ?: config.visibility,
)
