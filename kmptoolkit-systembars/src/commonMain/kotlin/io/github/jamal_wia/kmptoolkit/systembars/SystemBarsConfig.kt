package io.github.jamal_wia.kmptoolkit.systembars

/**
 * The colour of the icons and text a system bar draws — not the colour of the bar itself.
 *
 * The naming is deliberately literal. Every platform API in this area is named after the
 * *background* it expects ("light status bar", `isAppearanceLightStatusBars`) and therefore means
 * the opposite of what it says: "light" produces dark icons. That inversion is a reliable source of
 * bugs, so this enum names what you actually see.
 */
public enum class SystemBarIconStyle {

    /** Dark icons and text. Pick this when the content behind the bar is light. */
    DarkIcons,

    /** Light icons and text. Pick this when the content behind the bar is dark. */
    LightIcons,
}

/** What the system does with a bar that has been hidden. */
public enum class HiddenBarBehavior {

    /**
     * A hidden bar reappears translucently on a swipe from its edge, then hides itself again.
     * This is the right choice for a video player, a photo viewer, a game — anywhere the user
     * still needs a way back.
     */
    SwipeToReveal,

    /**
     * A hidden bar stays hidden until something shows it again. Nothing the user does at the
     * screen edge brings it back, so the app must offer its own way out — a kiosk or a dedicated
     * fullscreen mode.
     */
    StayHidden,
}

/**
 * Which bars are on screen, and what happens to the ones that are not.
 *
 * This is one axis of a [SystemBarsConfig], moved and overridden as a unit, because the two bars'
 * visibility is rarely decided independently and [hiddenBarBehavior] describes both of them.
 *
 * @property isStatusBarVisible whether the status bar is shown.
 * @property isNavigationBarVisible whether the navigation bar is shown. iOS has no navigation bar;
 *   this is ignored there.
 * @property hiddenBarBehavior what a hidden bar does. Irrelevant while both bars are visible.
 */
public data class SystemBarsVisibility(
    val isStatusBarVisible: Boolean = true,
    val isNavigationBarVisible: Boolean = true,
    val hiddenBarBehavior: HiddenBarBehavior = HiddenBarBehavior.SwipeToReveal,
) {
    public companion object {

        /** Both bars on screen. The default. */
        public val Visible: SystemBarsVisibility = SystemBarsVisibility()

        /** Both bars hidden, reappearing on a swipe. The usual fullscreen mode. */
        public val Immersive: SystemBarsVisibility = SystemBarsVisibility(
            isStatusBarVisible = false,
            isNavigationBarVisible = false,
            hiddenBarBehavior = HiddenBarBehavior.SwipeToReveal,
        )

        /** Both bars hidden and staying hidden. A swipe does not bring them back. */
        public val Hidden: SystemBarsVisibility = SystemBarsVisibility(
            isStatusBarVisible = false,
            isNavigationBarVisible = false,
            hiddenBarBehavior = HiddenBarBehavior.StayHidden,
        )
    }
}

/**
 * The complete state of the system bars, as three independently owned axes: the status bar's icon
 * style, the navigation bar's icon style, and [SystemBarsVisibility].
 *
 * The split into axes is the whole point. A screen that only cares about the status bar's icons
 * overrides that axis alone and leaves the other two to whoever owns them, so two screens with
 * different concerns never overwrite each other's work. See [SystemBarsOverride].
 *
 * There is no bar *background* colour here. On an edge-to-edge Android app the bars are
 * transparent and the colour behind them is whatever your own UI drew; `Window.statusBarColor` and
 * `Window.navigationBarColor` are deprecated and are no-ops from API 35 on. Paint that area in your
 * layout, not through this config.
 */
public data class SystemBarsConfig(
    val statusBarIcons: SystemBarIconStyle = SystemBarIconStyle.DarkIcons,
    val navigationBarIcons: SystemBarIconStyle = SystemBarIconStyle.DarkIcons,
    val visibility: SystemBarsVisibility = SystemBarsVisibility.Visible,
) {
    public companion object {

        /** Dark icons on both bars, both bars visible — for a light-themed app. */
        public val ForLightBackground: SystemBarsConfig = SystemBarsConfig(
            statusBarIcons = SystemBarIconStyle.DarkIcons,
            navigationBarIcons = SystemBarIconStyle.DarkIcons,
        )

        /** Light icons on both bars, both bars visible — for a dark-themed app. */
        public val ForDarkBackground: SystemBarsConfig = SystemBarsConfig(
            statusBarIcons = SystemBarIconStyle.LightIcons,
            navigationBarIcons = SystemBarIconStyle.LightIcons,
        )
    }
}
