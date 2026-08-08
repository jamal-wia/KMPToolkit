package io.github.jamal_wia.kmptoolkit.settings

import kotlin.jvm.JvmInline

/**
 * How much larger or smaller than its designed size the app's typography should be rendered, as a
 * plain multiplier: `1.0` is the design baseline, `1.3` is thirty percent larger.
 *
 * **This is a range, not a list of steps.** The library deliberately ships no `LARGE` /
 * `EXTRA_LARGE` constants: which steps an app offers, how many, and what they are called are
 * product and typography decisions that belong to the app, and a library that picked three of them
 * would be shipping someone else's type scale to everyone. Defining your own steps on top of this
 * type is a few lines — see `docs/kmptoolkit-settings/03-guide.md`:
 *
 * ```kotlin
 * enum class TextSize(val scale: FontScale) {
 *     STANDARD(FontScale.DEFAULT),
 *     LARGE(FontScale(1.15f)),
 *     EXTRA_LARGE(FontScale(1.30f)),
 * }
 * ```
 *
 * Applying it is the consuming app's job too — this module holds the value and persists it, it
 * does not render anything. In Compose that is one `CompositionLocalProvider` over
 * `LocalDensity`; scaling the density rather than only the font sizes is what keeps icons and
 * touch targets growing along with the text, which is the difference between a real accessibility
 * setting and one that fails an audit.
 *
 * @param multiplier the factor itself. Must be inside
 *   [MINIMUM_MULTIPLIER]`..`[MAXIMUM_MULTIPLIER] and must not be `NaN`; anything else throws
 *   [IllegalArgumentException]. Validated here rather than reported as a [SettingsError] for the
 *   same reason `StorageConfig` validates its name: a multiplier is a value a developer writes as
 *   a literal, so a wrong one is a bug to fix at the call site. A multiplier that arrives from
 *   *data* — a stored preference, a server response — goes through [of] instead, which returns
 *   `null` rather than throwing.
 */
@JvmInline
public value class FontScale(public val multiplier: Float) {

    init {
        require(isInRange(multiplier)) {
            "multiplier must be between $MINIMUM_MULTIPLIER and $MAXIMUM_MULTIPLIER, " +
                "was $multiplier"
        }
    }

    public companion object {

        /**
         * `0.5`. Below half the designed size, text stops being legible on a phone and hit targets
         * fall under every platform's minimum, so a smaller value is a bug rather than a choice.
         */
        public const val MINIMUM_MULTIPLIER: Float = 0.5f

        /**
         * `3.0`. The largest value any mainstream platform accessibility setting reaches — iOS's
         * largest accessibility text size lands near it, Android's font-size slider stops well
         * below — so it is the point past which no layout is expected to survive.
         */
        public const val MAXIMUM_MULTIPLIER: Float = 3.0f

        /** `1.0` — render at the size the app was designed at. The default for a fresh install. */
        public val DEFAULT: FontScale = FontScale(1.0f)

        /**
         * [multiplier] as a [FontScale], or `null` when it is outside
         * [MINIMUM_MULTIPLIER]`..`[MAXIMUM_MULTIPLIER] or is `NaN`.
         *
         * The non-throwing counterpart of the constructor, for a value that comes from data rather
         * than from source: a stored preference, a remote config, a text field.
         */
        public fun of(multiplier: Float): FontScale? =
            if (isInRange(multiplier)) FontScale(multiplier) else null

        /**
         * [multiplier] clamped into [MINIMUM_MULTIPLIER]`..`[MAXIMUM_MULTIPLIER], for a value that
         * has no meaningful failure path — the OS-reported font scale you want to mirror, a slider
         * position. `NaN` clamps to [DEFAULT], because it names no direction to clamp towards.
         */
        public fun coerced(multiplier: Float): FontScale = when {
            multiplier.isNaN() -> DEFAULT
            else -> FontScale(multiplier.coerceIn(MINIMUM_MULTIPLIER, MAXIMUM_MULTIPLIER))
        }

        /**
         * The single definition of "acceptable", shared by the constructor and [of] so the two can
         * never disagree about a boundary.
         *
         * `in` on a `Float` range is a `compareTo` chain, so `NaN` — which compares false against
         * everything — is rejected by the same check rather than needing its own.
         */
        internal fun isInRange(multiplier: Float): Boolean =
            multiplier in MINIMUM_MULTIPLIER..MAXIMUM_MULTIPLIER
    }
}
